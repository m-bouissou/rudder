// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod cmd;
mod config;
mod database;
mod dependency;
mod plugin;
mod repo_index;
mod repository;
mod signature;
mod versions;
mod webapp;

use std::{
    path::{Path, PathBuf},
    process,
};

use crate::{cli::Command, signature::SignatureVerifier, webapp::Webapp};
use anyhow::{Context, Result};
use clap::Parser;
use log::{debug, error, LevelFilter};

use crate::{config::Configuration, repository::Repository};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";
const SIGNATURE_KEYRING_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg";
const RUDDER_VERSION_PATH: &str = "/opt/rudder/share/versions/rudder-server-version";
const REPOSITORY_INDEX_PATH: &str = "/var/rudder/tmp/plugins/rpkg.index";
const TMP_PLUGINS_FOLDER: &str = "/var/rudder/tmp/plugins";

fn am_i_root() -> Result<bool> {
    let out = process::Command::new("id").arg("--user").output()?;
    let uid = String::from_utf8_lossy(&out.stdout)
        .strip_suffix('\n')
        .unwrap()
        .parse::<usize>()?;
    Ok(uid == 0)
}

/// CLI entry point
pub fn run() -> Result<()> {
    // Abort of not run as root
    // Ignore on error
    #[cfg(not(debug_assertions))]
    if let Ok(false) = am_i_root() {
        eprintln!("This program needs to run as root, aborting.");
        process::exit(1);
    }

    let args = cli::Args::parse();
    let filter = if args.debug {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    };
    env_logger::builder()
        .format_timestamp(None)
        .format_module_path(false)
        .format_target(false)
        .filter_level(filter)
        .init();
    debug!("Parsed CLI arguments: {args:?}");
    let cfg = Configuration::read(Path::new(&args.config))
        .with_context(|| format!("Reading configuration from '{}'", &args.config))?;
    let verifier = SignatureVerifier::new(PathBuf::from(SIGNATURE_KEYRING_PATH));
    let repo = Repository::new(&cfg, verifier)?;
    let mut webapp = Webapp::new(PathBuf::from(WEBAPP_XML_PATH));
    debug!("Parsed configuration: {cfg:?}");

    match args.command {
        Command::Install { force, package } => {
            return action::install(force, package, repo, &mut webapp);
        }
        Command::Uninstall { package } => {
            return action::uninstall(package, &mut webapp);
        }
        _ => {
            error!("This command is not implemented");
        }
    }
    Ok(())
}

pub mod action {
    use anyhow::{anyhow, bail, Result};
    use flate2::read::GzDecoder;
    use log::debug;
    use tar::Archive;

    use crate::archive::Rpkg;
    use crate::database::Database;
    use crate::repo_index::RepoIndex;
    use crate::repository::Repository;
    use crate::versions::RudderVersion;
    use crate::{
        webapp::Webapp, PACKAGES_DATABASE_PATH, REPOSITORY_INDEX_PATH, RUDDER_VERSION_PATH,
        TMP_PLUGINS_FOLDER,
    };
    use std::fs;
    use std::fs::File;
    use std::io::BufRead;
    use std::path::{Path, PathBuf};

    pub fn uninstall(packages: Vec<String>, webapp: &mut Webapp) -> Result<()> {
        let mut db = Database::read(PACKAGES_DATABASE_PATH)?;
        packages.iter().try_for_each(|p| db.uninstall(p, webapp))
    }

    pub fn install(
        force: bool,
        packages: Vec<String>,
        repository: Repository,
        webapp: &mut Webapp,
    ) -> Result<()> {
        packages.iter().try_for_each(|package| {
            let rpkg_path = if Path::new(&package).exists() {
                package.clone()
            } else {
                // Find compatible plugin if any
                let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
                let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
                let to_dl_and_install = match index.get_compatible_plugin(webapp_version, package) {
                    None => bail!("Could not find any compatible '{}' plugin with the current Rudder version in the configured repository.", package),
                    Some(p) => {
                        debug!("Found a compatible plugin in the repository:\n{:?}", p);
                        p
                    }
                };
                let dest = Path::new(TMP_PLUGINS_FOLDER).join(
                    Path::new(&to_dl_and_install.path)
                        .file_name()
                        .ok_or_else(|| {
                            anyhow!(
                                "Could not retrieve filename from path '{}'",
                                to_dl_and_install.path
                            )
                        })?,
                );
                // Download rpkg
                repository.clone().download(&to_dl_and_install.path, &dest)?;
                dest.as_path().display().to_string()
            };
            let rpkg = Rpkg::from_path(&rpkg_path)?;
            rpkg.install(force, webapp)?;
            Ok(())
        })?;
        webapp.apply_changes()
    }

    pub fn list() -> Result<()> {
        let db = Database::read(PACKAGES_DATABASE_PATH);
        println!("Installed plugins:\n{:?}", db);
        Ok(())
    }

    pub fn update(repository: Repository) -> Result<()> {
        // Update the index file
        debug!("Updating repository index");
        let rudder_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
        let remote_index = format!(
            "{}.{}/rpkg.index",
            rudder_version.major, rudder_version.minor
        );
        repository.download_unsafe(&remote_index, &PathBuf::from(REPOSITORY_INDEX_PATH))?;

        // Update the licenses
        if let Some(x) = repository.get_username() {
            debug!("Updating licenses");
            let license_folder = PathBuf::from("/opt/rudder/etc/plugins/licenses");
            let archive_name = format!("{}-license.tar.gz", x);
            let local_archive_path = &license_folder.clone().join(archive_name.clone());
            if let Err(e) = repository.download_unsafe(
                &format!("licences/{}/{}", x, archive_name),
                local_archive_path,
            ) {
                bail!(
                    "Could not download licenses from configured repository.\n{}",
                    e
                )
            }
            // Decompress archive
            let mut archive = Archive::new(GzDecoder::new(File::open(local_archive_path)?));
            archive.unpack(license_folder)?;
        } else {
            debug!("Not updating licenses as no configured credentials were found")
        }
        Ok(())
    }

    pub fn enable(
        mut w: Webapp,
        packages: Option<Vec<String>>,
        all: bool,
        snapshot: bool,
        restore: bool,
        backup_path: Option<String>,
    ) -> Result<()> {
        let db = Database::read(PACKAGES_DATABASE_PATH)?;
        // If all is passed, enabled all installed plugins
        let to_enabled = if all {
            Some(db.plugins.keys().cloned().collect())
        } else {
            packages
        };
        // If package names are passed, enabled them
        if let Some(x) = to_enabled {
            return x.iter().try_for_each(|p| match db.plugins.get(p) {
                None => {
                    println!("Plugin {} not found installed", p);
                    Ok(())
                }
                Some(installed_plugin) => installed_plugin.enable(&mut w),
            });
        }
        let backup_path = match backup_path {
            None => format!("{}/plugins_status.backup", TMP_PLUGINS_FOLDER),
            Some(p) => p,
        };
        if snapshot {
            let mut enabled_jars_from_plugins = Vec::<String>::new();
            let enabled_jar = w.jars()?;
            for (k, v) in db.plugins.clone() {
                if let Some(j) = v.metadata.jar_files {
                    if j.iter().any(|x| enabled_jar.contains(x)) {
                        enabled_jars_from_plugins.push(format!("enable {}", k))
                    }
                }
            }
            fs::write(backup_path, enabled_jars_from_plugins.join("\n"))?;
            return Ok(());
        }

        if restore {
            let file = File::open(backup_path)?;
            let buf = std::io::BufReader::new(file);
            buf.lines().for_each(|l| {
                let binding = l.expect("Could not read line from plugin backup status file");
                let plugin_name = binding.trim().split(' ').nth(1);
                match plugin_name {
                    None => debug!("Malformed line in plugin backup status file"),
                    Some(x) => match db.plugins.get(x) {
                        None => debug!("Plugin {} is not installed, it could not be enabled", x),
                        Some(y) => {
                            let _ = y.enable(&mut w);
                        }
                    },
                }
            })
        }
        Ok(())
    }
}
