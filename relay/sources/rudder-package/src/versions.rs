// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::{bail, Error, Ok, Result};
use regex::Regex;
use std::cmp::Ordering;
use std::str::FromStr;

struct ArchiveVersion {
    pub rudder_version: RudderVersion,
    pub plugin_version: PluginVersion,
}

impl FromStr for ArchiveVersion {
    type Err = Error;
    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        let split = match s.split_once('-') {
            None => bail!("Unparsable rpkg version '{}'", s),
            Some(c) => c,
        };
        let rudder_version = RudderVersion::from_str(split.0)?;
        let plugin_version = PluginVersion::from_str(split.1)?;
        Ok(Self {
            rudder_version,
            plugin_version,
        })
    }
}

#[derive(PartialEq, Debug)]
enum RudderVersionMode {
    Alpha { version: u32 },
    Beta { version: u32 },
    Rc { version: u32 },
    Final,
}

impl FromStr for RudderVersionMode {
    type Err = Error;
    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        // If the mode is empty, it is a "plain" release
        let alpha_regex = Regex::new(r"^~alpha(?<version>\d+).*")?;
        let beta_regex = Regex::new(r"^~beta(?<version>\d+).*")?;
        let rc_regex = Regex::new(r"^~rc(?<version>\d+).*")?;
        let git_regex = Regex::new(r"^~git(?<version>\d+)")?;
        if s.is_empty() {
            return Ok(RudderVersionMode::Final);
        }
        // Test if alpha
        match alpha_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Alpha { version });
            }
        };
        // Test if beta
        match beta_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Beta { version });
            }
        };
        // Test if rc
        match rc_regex.captures(s) {
            None => (),
            Some(c) => {
                let version = c["version"].to_string().parse().unwrap();
                return Ok(RudderVersionMode::Rc { version });
            }
        };
        // Test if git
        match git_regex.captures(s) {
            None => (),
            Some(_) => return Ok(RudderVersionMode::Final),
        };
        bail!("Unparsable Rudder version mode '{}'", s)
    }
}

// Checking if a rudder version is a nightly or not is not important for plugin compatibility
// So it is not implemented
#[derive(PartialEq, Debug)]
struct RudderVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub mode: RudderVersionMode,
}

impl FromStr for RudderVersion {
    type Err = Error;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        let re = Regex::new(r"^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?<mode>.*)$")?;
        let caps = match re.captures(raw) {
            None => bail!("Unparsable rudder version '{}'", raw),
            Some(c) => c,
        };
        let major: u32 = caps["major"].parse()?;
        let minor: u32 = caps["minor"].parse()?;
        let patch: u32 = caps["patch"].parse()?;
        let mode: RudderVersionMode = RudderVersionMode::from_str(&caps["mode"])?;

        Ok(RudderVersion {
            major,
            minor,
            patch,
            mode,
        })
    }
}

#[derive(PartialEq, Debug)]
struct PluginVersion {
    pub major: u32,
    pub minor: u32,
    pub nightly: bool,
}

impl FromStr for PluginVersion {
    type Err = Error;

    fn from_str(raw: &str) -> Result<Self, Self::Err> {
        let nightly = Regex::new(r".*-nightly$")?.is_match(raw);
        let re = Regex::new(r"^(?<major>\d+)\.(?<minor>\d+)(-nightly)?$")?;
        let caps = match re.captures(raw) {
            None => bail!("Unparsable plugin version '{}'", raw),
            Some(c) => c,
        };
        let major: u32 = caps["major"].parse()?;
        let minor: u32 = caps["minor"].parse()?;

        Ok(PluginVersion {
            major,
            minor,
            nightly,
        })
    }
}
impl PartialOrd for PluginVersion {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        if self.major < other.major {
            Some(Ordering::Less)
        } else if self.major > other.major {
            Some(Ordering::Greater)
        } else if self.minor < other.minor {
            Some(Ordering::Less)
        } else if self.minor > other.minor {
            Some(Ordering::Greater)
        } else if self.nightly && !other.nightly {
            Some(Ordering::Less)
        } else if !self.nightly && other.nightly {
            Some(Ordering::Greater)
        } else {
            Some(Ordering::Equal)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;
    use rstest::rstest;

    #[rstest]
    #[case("7.0.0~alpha2", 7, 0, 0, "~alpha2")]
    #[case("7.0.0", 7, 0, 0, "")]
    #[case("8.0.1~rc1", 8, 0, 1, "~rc1")]
    fn test_rudder_version_parsing(
        #[case] raw: &str,
        #[case] e_major: u32,
        #[case] e_minor: u32,
        #[case] e_patch: u32,
        #[case] e_mode: &str,
    ) {
        let v = RudderVersion::from_str(raw).unwrap();
        assert_eq!(v.major, e_major);
        assert_eq!(v.minor, e_minor);
        assert_eq!(v.patch, e_patch);
        assert_eq!(v.mode, RudderVersionMode::from_str(e_mode).unwrap());
    }

    #[rstest]
    #[should_panic]
    #[case("7.0.0-alpha2")]
    #[should_panic]
    #[case("7.0.0.0~alpha2")]
    #[should_panic]
    #[case("7.0.0alpha2")]
    fn test_rudder_version_parsing_errors(#[case] raw: &str) {
        let _ = RudderVersion::from_str(raw).unwrap();
    }

    #[rstest]
    #[case("11.0", "2.99")]
    #[case("1.20", "1.12")]
    #[case("2.3", "2.3-nightly")]
    fn test_plugin_version_greater_than(#[case] a: &str, #[case] b: &str) {
        let left = PluginVersion::from_str(a).unwrap();
        let right = PluginVersion::from_str(b).unwrap();
        assert!(left > right, "{:?} is not less than {:?}", left, right);
    }
    #[rstest]
    #[case("8.0.0-1.1")]
    #[case("8.0.0-1.1-nightly")]
    #[case("8.0.0-1.12")]
    #[case("8.0.0-1.12-nightly")]
    #[case("8.0.0-2.0-nightly")]
    #[case("8.0.0-2.1")]
    #[case("8.0.0-2.1-nightly")]
    #[case("8.0.0-2.2")]
    #[case("8.0.0-2.2-nightly")]
    #[case("8.0.0-2.3")]
    #[case("8.0.0-2.4")]
    #[case("8.0.0-2.4-nightly")]
    #[case("8.0.0-2.7")]
    #[case("8.0.0-2.9")]
    #[case("8.0.0-2.9-nightly")]
    #[case("8.0.0~alpha1-1.1")]
    #[case("8.0.0~alpha1-1.1-nightly")]
    #[case("8.0.0~alpha1-1.12")]
    #[case("8.0.0~alpha1-1.12-nightly")]
    #[case("8.0.0~alpha1-2.0-nightly")]
    #[case("8.0.0~alpha1-2.1")]
    #[case("8.0.0~alpha1-2.1-nightly")]
    #[case("8.0.0~alpha1-2.2")]
    #[case("8.0.0~alpha1-2.2-nightly")]
    #[case("8.0.0~alpha1-2.3")]
    #[case("8.0.0~alpha1-2.4")]
    #[case("8.0.0~alpha1-2.4-nightly")]
    #[case("8.0.0~alpha1-2.6")]
    #[case("8.0.0~alpha1-2.9")]
    #[case("8.0.0~alpha1-2.9-nightly")]
    #[case("8.0.0~alpha2-2.0-nightly")]
    #[case("8.0.0~alpha2-2.1-nightly")]
    #[case("8.0.0~alpha2-2.2-nightly")]
    #[case("8.0.0~beta1-1.1")]
    #[case("8.0.0~beta1-1.1-nightly")]
    #[case("8.0.0~beta1-1.12")]
    #[case("8.0.0~beta1-1.12-nightly")]
    #[case("8.0.0~beta1-2.1")]
    #[case("8.0.0~beta1-2.1-nightly")]
    #[case("8.0.0~beta1-2.2")]
    #[case("8.0.0~beta1-2.3")]
    #[case("8.0.0~beta1-2.4")]
    #[case("8.0.0~beta1-2.4-nightly")]
    #[case("8.0.0~beta1-2.7")]
    #[case("8.0.0~beta1-2.9")]
    #[case("8.0.0~beta1-2.9-nightly")]
    #[case("8.0.0~beta2-1.1")]
    #[case("8.0.0~beta2-1.1-nightly")]
    #[case("8.0.0~beta2-1.12")]
    #[case("8.0.0~beta2-1.12-nightly")]
    #[case("8.0.0~beta2-2.0-nightly")]
    #[case("8.0.0~beta2-2.1")]
    #[case("8.0.0~beta2-2.1-nightly")]
    #[case("8.0.0~beta2-2.2")]
    #[case("8.0.0~beta2-2.2-nightly")]
    #[case("8.0.0~beta2-2.3")]
    #[case("8.0.0~beta2-2.4")]
    #[case("8.0.0~beta2-2.4-nightly")]
    #[case("8.0.0~beta2-2.7")]
    #[case("8.0.0~beta2-2.9")]
    #[case("8.0.0~beta2-2.9-nightly")]
    #[case("8.0.0~beta3-1.1")]
    #[case("8.0.0~beta3-1.1-nightly")]
    #[case("8.0.0~beta3-1.12")]
    #[case("8.0.0~beta3-1.12-nightly")]
    #[case("8.0.0~beta3-2.1")]
    #[case("8.0.0~beta3-2.1-nightly")]
    #[case("8.0.0~beta3-2.2")]
    #[case("8.0.0~beta3-2.3")]
    #[case("8.0.0~beta3-2.4")]
    #[case("8.0.0~beta3-2.4-nightly")]
    #[case("8.0.0~beta3-2.7")]
    #[case("8.0.0~beta3-2.9")]
    #[case("8.0.0~beta3-2.9-nightly")]
    #[case("8.0.0~beta4-2.0-nightly")]
    #[case("8.0.0~beta4-2.1-nightly")]
    #[case("8.0.0~beta4-2.2-nightly")]
    #[case("8.0.0~rc1-1.1")]
    #[case("8.0.0~rc1-1.1-nightly")]
    #[case("8.0.0~rc1-1.12")]
    #[case("8.0.0~rc1-1.12-nightly")]
    #[case("8.0.0~rc1-2.0-nightly")]
    #[case("8.0.0~rc1-2.1")]
    #[case("8.0.0~rc1-2.1-nightly")]
    #[case("8.0.0~rc1-2.2")]
    #[case("8.0.0~rc1-2.2-nightly")]
    #[case("8.0.0~rc1-2.3")]
    #[case("8.0.0~rc1-2.4")]
    #[case("8.0.0~rc1-2.4-nightly")]
    #[case("8.0.0~rc1-2.7")]
    #[case("8.0.0~rc1-2.9")]
    #[case("8.0.0~rc1-2.9-nightly")]
    #[case("8.0.0~rc2-1.1")]
    #[case("8.0.0~rc2-1.1-nightly")]
    #[case("8.0.0~rc2-1.12")]
    #[case("8.0.0~rc2-1.12-nightly")]
    #[case("8.0.0~rc2-2.0-nightly")]
    #[case("8.0.0~rc2-2.1")]
    #[case("8.0.0~rc2-2.1-nightly")]
    #[case("8.0.0~rc2-2.2")]
    #[case("8.0.0~rc2-2.2-nightly")]
    #[case("8.0.0~rc2-2.3")]
    #[case("8.0.0~rc2-2.4")]
    #[case("8.0.0~rc2-2.4-nightly")]
    #[case("8.0.0~rc2-2.7")]
    #[case("8.0.0~rc2-2.9")]
    #[case("8.0.0~rc2-2.9-nightly")]
    #[case("8.0.0~rc3-2.0-nightly")]
    #[case("8.0.0~rc3-2.1-nightly")]
    #[case("8.0.0~rc3-2.2-nightly")]
    #[case("8.0.1-1.1")]
    #[case("8.0.1-1.1-nightly")]
    #[case("8.0.1-1.12")]
    #[case("8.0.1-1.12-nightly")]
    #[case("8.0.1-2.0-nightly")]
    #[case("8.0.1-2.1")]
    #[case("8.0.1-2.1-nightly")]
    #[case("8.0.1-2.2")]
    #[case("8.0.1-2.2-nightly")]
    #[case("8.0.1-2.3")]
    #[case("8.0.1-2.4")]
    #[case("8.0.1-2.4-nightly")]
    #[case("8.0.1-2.7")]
    #[case("8.0.1-2.9")]
    #[case("8.0.1-2.9-nightly")]
    #[case("8.0.2-2.0-nightly")]
    #[case("8.0.2-2.1-nightly")]
    #[case("8.0.2-2.2-nightly")]
    fn test_rpkg_version(#[case] a: &str) {
        let _ = ArchiveVersion::from_str(a).unwrap();
    }
}
