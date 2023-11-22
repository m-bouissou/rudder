// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::{anyhow, bail, Context, Ok, Result};
use ar::Archive;
use core::fmt;
use log::debug;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, *},
    io::{Cursor, Read},
    path::{Path, PathBuf},
    process::Command,
};

use crate::{
    cmd::CmdOutput,
    database::{Database, InstalledPlugin},
    plugin::Metadata,
    versions::RudderVersion,
    webapp_xml::WebappXml,
    PACKAGES_DATABASE_PATH, PACKAGES_FOLDER, RUDDER_VERSION_PATH, WEBAPP_XML_PATH,
};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone, Copy)]
pub enum PackageType {
    #[serde(rename = "plugin")]
    Plugin,
}

impl fmt::Display for PackageType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageType::Plugin => write!(f, "plugin"),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum PackageScript {
    Postinst,
    Postrm,
    Preinst,
    Prerm,
}

impl fmt::Display for PackageScript {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageScript::Postinst => write!(f, "postinst"),
            PackageScript::Postrm => write!(f, "postrm"),
            PackageScript::Preinst => write!(f, "preinst"),
            PackageScript::Prerm => write!(f, "prerm"),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum PackageScriptArg {
    Install,
    Upgrade,
    None,
}

impl fmt::Display for PackageScriptArg {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageScriptArg::Install => write!(f, "install"),
            PackageScriptArg::Upgrade => write!(f, "upgrade"),
            PackageScriptArg::None => write!(f, ""),
        }
    }
}

#[derive(Clone)]
pub struct Rpkg {
    pub path: String,
    pub metadata: Metadata,
}

impl Rpkg {
    pub fn from_path(path: &str) -> Result<Rpkg> {
        let r = Rpkg {
            path: String::from(path),
            metadata: read_metadata(path).unwrap(),
        };
        Ok(r)
    }

    fn get_txz_dst(&self, txz_name: &str) -> String {
        // Build the destination path
        if txz_name == "scripts.txz" {
            return PACKAGES_FOLDER.to_string();
        }
        return self.metadata.content.get(txz_name).unwrap().to_string();
    }

    fn get_archive_installed_files(&self) -> Result<Vec<String>> {
        let mut txz_names = self.get_txz_list()?;
        txz_names.retain(|x| x != "scripts.txz");
        let f = txz_names
            .iter()
            .map(|x| self.get_absolute_file_list_of_txz(x))
            .collect::<Result<Vec<_>>>()?;
        Ok(f.into_iter().flatten().collect::<Vec<String>>())
    }

    fn get_txz_list(&self) -> Result<Vec<String>> {
        let mut txz_names = Vec::<String>::new();
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let name = std::str::from_utf8(entry_result?.header().identifier())?.to_string();
            if name.ends_with(".txz") {
                txz_names.push(name);
            }
        }
        Ok(txz_names)
    }

    fn get_relative_file_list_of_txz(&self, txz_name: &str) -> Result<Vec<String>> {
        let mut file_list = Vec::<String>::new();
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = std::str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive
                .entries()?
                .filter_map(|e| e.ok())
                .for_each(|entry| {
                    let a = entry
                        .path()
                        .unwrap()
                        .into_owned()
                        .to_string_lossy()
                        .to_string();
                    file_list.push(a);
                })
        }
        Ok(file_list)
    }

    fn get_absolute_file_list_of_txz(&self, txz_name: &str) -> Result<Vec<String>> {
        let prefix = self.get_txz_dst(txz_name);
        let relative_files = self.get_relative_file_list_of_txz(txz_name)?;
        Ok(relative_files
            .iter()
            .map(|x| -> PathBuf { Path::new(&prefix.clone()).join(x) })
            .map(|y| y.to_str().ok_or(anyhow!("err")).map(|z| z.to_owned()))
            .collect::<Result<Vec<String>>>()?)
    }

    fn unpack_embedded_txz(&self, txz_name: &str, dst_path: &str) -> Result<(), anyhow::Error> {
        debug!("Extracting archive '{}' in folder '{}'", txz_name, dst_path);
        let dst = Path::new(dst_path);
        // Loop over ar archive files
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = std::str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let parent = dst.parent().unwrap();
            // Verify that the directory structure exists
            fs::create_dir_all(parent).with_context(|| {
                format!("Make sure the folder '{}' exists", parent.to_str().unwrap(),)
            })?;
            // Unpack the txz archive
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive.unpack(dst)?;
            return Ok(());
        }
        Ok(())
    }

    pub fn is_installed(&self) -> Result<bool> {
        let current_database = Database::read(PACKAGES_DATABASE_PATH)?;
        Ok(current_database.is_installed(self.to_owned()))
    }

    pub fn install(&self, force: bool) -> Result<()> {
        debug!("Installing rpkg '{}'...", self.path);
        // Verify webapp compatibility
        let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
        if !(force
            || self
                .metadata
                .version
                .rudder_version
                .is_compatible(&webapp_version.to_string()))
        {
            bail!("This plugin was built for a Rudder '{}', it is incompatible with your current webapp version '{}'.", self.metadata.version.rudder_version, webapp_version)
        }
        // Verify that dependencies are installed
        if let Some(d) = &self.metadata.depends {
            if !(force || d.are_installed()) {
                bail!("Some dependencies are missing, install them before trying to install the plugin.")
            }
        }
        // Extract package scripts
        self.unpack_embedded_txz("script.txz", PACKAGES_FOLDER)?;
        // Run preinst if any
        let install_or_upgrade: PackageScriptArg = PackageScriptArg::Install;
        self.run_package_script(PackageScript::Preinst, install_or_upgrade)?;
        // Extract archive content
        let keys = self.metadata.content.keys().clone();
        for txz_name in keys {
            let dst = self.get_txz_dst(txz_name);
            self.unpack_embedded_txz(txz_name, &dst)?
        }
        // Update the plugin index file to track installed files
        // We need to add the content section to the metadata to do so
        let mut db = Database::read(PACKAGES_DATABASE_PATH)?;
        db.plugins.insert(
            self.metadata.name.clone(),
            InstalledPlugin {
                files: self.get_archive_installed_files()?,
                metadata: self.metadata.clone(),
            },
        );
        Database::write(PACKAGES_DATABASE_PATH, db)?;
        // Run postinst if any
        let install_or_upgrade: PackageScriptArg = PackageScriptArg::Install;
        self.run_package_script(PackageScript::Postinst, install_or_upgrade)?;
        // Update the webapp xml file if the plugin contains one or more jar file
        debug!("Enabling the associated jars if any");
        match self.metadata.jar_files.clone() {
            None => (),
            Some(jars) => {
                let w = WebappXml::new(String::from(WEBAPP_XML_PATH));
                for jar_path in jars.into_iter() {
                    w.enable_jar(jar_path)?;
                }
            }
        }
        // Restarting webapp
        debug!("Install completed");
        Ok(())
    }

    fn run_package_script(&self, script: PackageScript, arg: PackageScriptArg) -> Result<()> {
        debug!(
            "Running package script '{}' with args '{}' for rpkg '{}'...",
            script, arg, self.path
        );
        let package_script_path = Path::new(PACKAGES_FOLDER)
            .join(self.metadata.name.clone())
            .join(script.to_string());
        if !package_script_path.exists() {
            debug!("Skipping as the script does not exist.");
            return Ok(());
        }
        let mut binding = Command::new(package_script_path);
        let cmd = binding.arg(arg.to_string());
        let r = match CmdOutput::new(cmd) {
            std::result::Result::Ok(a) => a,
            Err(e) => {
                bail!("Could not execute package script '{}'`n{}", script, e);
            }
        };
        if !r.output.status.success() {
            debug!("Package script execution return unexpected exit code.");
        }
        Ok(())
    }
}

fn read_metadata(path: &str) -> Result<Metadata> {
    let mut archive = Archive::new(File::open(path).unwrap());
    while let Some(entry_result) = archive.next_entry() {
        let mut entry = entry_result.unwrap();
        let mut buffer = String::new();
        let entry_title = std::str::from_utf8(entry.header().identifier()).unwrap();
        if entry_title == "metadata" {
            let _ = entry.read_to_string(&mut buffer)?;
            let m: Metadata = serde_json::from_str(&buffer)
                .with_context(|| format!("Failed to parse {} metadata", path))?;
            return Ok(m);
        };
    }
    anyhow::bail!("No metadata found in {}", path);
}

#[cfg(test)]
mod tests {
    use tempfile::tempdir;

    use super::*;
    extern crate dir_diff;

    #[test]
    fn test_read_rpkg_metadata() {
        assert!(read_metadata("./tests/malformed_metadata.rpkg").is_err());
        assert!(read_metadata("./tests/without_metadata.rpkg").is_err());
        read_metadata("./tests/with_metadata.rpkg").unwrap();
    }

    #[test]
    fn test_get_relative_file_list_of_txz() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_relative_file_list_of_txz("files.txz").unwrap(),
            vec![
                "share/",
                "share/python/",
                "share/python/glpi.py",
                "share/python/notifyd.py"
            ]
        );
        assert_eq!(
            r.get_relative_file_list_of_txz("scripts.txz").unwrap(),
            vec!["postinst"]
        );
    }

    #[test]
    fn test_get_absolute_file_list_of_txz() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_absolute_file_list_of_txz("files.txz").unwrap(),
            vec![
                "/opt/rudder/share/",
                "/opt/rudder/share/python/",
                "/opt/rudder/share/python/glpi.py",
                "/opt/rudder/share/python/notifyd.py"
            ]
        );
        assert_eq!(
            r.get_absolute_file_list_of_txz("scripts.txz").unwrap(),
            vec!["/var/rudder/packages/postinst"]
        );
    }

    #[test]
    fn test_get_archive_installed_filed() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_archive_installed_files().unwrap(),
            vec![
                "/opt/rudder/share/",
                "/opt/rudder/share/python/",
                "/opt/rudder/share/python/glpi.py",
                "/opt/rudder/share/python/notifyd.py"
            ]
        );
    }

    #[test]
    fn test_extract_txz_from_rpkg() {
        let bind;
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        let expected_dir_content = "./tests/archive/expected_dir_content";
        let effective_target = {
            let real_unpack_target = r.get_txz_dst("files.txz");
            let trimmed = Path::new(&real_unpack_target).strip_prefix("/").unwrap();
            bind = tempdir().unwrap().into_path().join(trimmed);
            bind.to_str().unwrap()
        };
        r.unpack_embedded_txz("files.txz", effective_target)
            .unwrap();
        assert!(!dir_diff::is_different(effective_target, expected_dir_content).unwrap());
    }
}
