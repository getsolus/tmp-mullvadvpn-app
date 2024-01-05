use futures::channel::oneshot;
use shell_escape;
use std::{
    ffi::{OsStr, OsString},
    fmt, io,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use talpid_types::net::{self, proxy::CustomProxy};

static BASE_ARGUMENTS: &[&[&str]] = &[
    &["--client"],
    &["--tls-client"],
    &["--nobind"],
    &["--mute-replay-warnings"],
    #[cfg(not(windows))]
    &["--dev", "tun"],
    #[cfg(windows)]
    &["--dev-type", "tun"],
    &["--ping", "4"],
    &["--ping-exit", "25"],
    &["--connect-timeout", "30"],
    &["--connect-retry", "0", "0"],
    &["--connect-retry-max", "1"],
    &["--remote-cert-tls", "server"],
    &["--rcvbuf", "1048576"],
    &["--sndbuf", "1048576"],
    &["--fast-io"],
    &["--data-ciphers-fallback", "AES-256-GCM"],
    &["--tls-version-min", "1.3"],
    &["--verb", "3"],
    #[cfg(windows)]
    &[
        "--route-gateway",
        "dhcp",
        "--route",
        "0.0.0.0",
        "0.0.0.0",
        "vpn_gateway",
        "1",
    ],
    // The route manager is used to add the routes.
    #[cfg(target_os = "linux")]
    &["--route-noexec"],
    #[cfg(windows)]
    &["--ip-win32", "ipapi"],
    #[cfg(windows)]
    &["--windows-driver", "wintun"],
];

static ALLOWED_TLS1_3_CIPHERS: &[&str] =
    &["TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"];

/// An OpenVPN process builder, providing control over the different arguments that the OpenVPN
/// binary accepts.
#[derive(Clone)]
pub struct OpenVpnCommand {
    openvpn_bin: OsString,
    config: Option<PathBuf>,
    remote: Option<net::Endpoint>,
    user_pass_path: Option<PathBuf>,
    proxy_auth_path: Option<PathBuf>,
    ca: Option<PathBuf>,
    crl: Option<PathBuf>,
    plugin: Option<(PathBuf, Vec<String>)>,
    log: Option<PathBuf>,
    tunnel_options: net::openvpn::TunnelOptions,
    proxy_settings: Option<CustomProxy>,
    tunnel_alias: Option<OsString>,
    enable_ipv6: bool,
    proxy_port: Option<u16>,
    #[cfg(target_os = "linux")]
    fwmark: Option<u32>,
}

impl OpenVpnCommand {
    /// Constructs a new `OpenVpnCommand` for launching OpenVPN processes from the binary at
    /// `openvpn_bin`.
    pub fn new<P: AsRef<OsStr>>(openvpn_bin: P) -> Self {
        OpenVpnCommand {
            openvpn_bin: OsString::from(openvpn_bin.as_ref()),
            config: None,
            remote: None,
            user_pass_path: None,
            proxy_auth_path: None,
            ca: None,
            crl: None,
            plugin: None,
            log: None,
            tunnel_options: net::openvpn::TunnelOptions::default(),
            proxy_settings: None,
            tunnel_alias: None,
            enable_ipv6: true,
            proxy_port: None,
            #[cfg(target_os = "linux")]
            fwmark: None,
        }
    }

    /// Sets what the firewall mark should be
    #[cfg(target_os = "linux")]
    pub fn fwmark(&mut self, fwmark: Option<u32>) -> &mut Self {
        self.fwmark = fwmark;
        self
    }

    /// Sets what configuration file will be given to OpenVPN
    pub fn config(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.config = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets the address and protocol that OpenVPN will connect to.
    pub fn remote(&mut self, remote: net::Endpoint) -> &mut Self {
        self.remote = Some(remote);
        self
    }

    /// Sets the path to the file where the username and password for user-pass authentication
    /// is stored. See the `--auth-user-pass` OpenVPN documentation for details.
    pub fn user_pass(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.user_pass_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets the path to the file where the username and password for proxy authentication
    /// is stored.
    pub fn proxy_auth(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.proxy_auth_path = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets the path to the CA certificate file.
    pub fn ca(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.ca = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets the path to the CRL (Certificate revocation list) file.
    pub fn crl(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.crl = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets a plugin and its arguments that OpenVPN will be started with.
    pub fn plugin(&mut self, path: impl AsRef<Path>, args: Vec<String>) -> &mut Self {
        self.plugin = Some((path.as_ref().to_path_buf(), args));
        self
    }

    /// Sets a log file path.
    pub fn log(&mut self, path: impl AsRef<Path>) -> &mut Self {
        self.log = Some(path.as_ref().to_path_buf());
        self
    }

    /// Sets extra options
    pub fn tunnel_options(&mut self, tunnel_options: &net::openvpn::TunnelOptions) -> &mut Self {
        self.tunnel_options = tunnel_options.clone();
        self
    }

    /// Sets the tunnel alias which will be used to identify a tunnel device that will be used by
    /// OpenVPN.
    pub fn tunnel_alias(&mut self, tunnel_alias: Option<OsString>) -> &mut Self {
        self.tunnel_alias = tunnel_alias;
        self
    }

    /// Configures if IPv6 should be allowed in the tunnel.
    pub fn enable_ipv6(&mut self, enable_ipv6: bool) -> &mut Self {
        self.enable_ipv6 = enable_ipv6;
        self
    }

    /// Sets the local proxy port bound to.
    /// In case of dynamic port selection, this will only be known after the proxy has been started.
    pub fn proxy_port(&mut self, proxy_port: u16) -> &mut Self {
        self.proxy_port = Some(proxy_port);
        self
    }

    /// Sets the proxy settings.
    pub fn proxy_settings(&mut self, proxy_settings: CustomProxy) -> &mut Self {
        self.proxy_settings = Some(proxy_settings);
        self
    }

    /// Build a runnable expression from the current state of the command.
    pub fn build(&self) -> tokio::process::Command {
        log::debug!("Building expression: {}", &self);
        let mut handle = tokio::process::Command::new(&self.openvpn_bin);
        handle.args(self.get_arguments());
        handle
    }

    /// Returns all arguments that the subprocess would be spawned with.
    fn get_arguments(&self) -> Vec<OsString> {
        let mut args: Vec<OsString> = Self::base_arguments().iter().map(OsString::from).collect();

        if let Some(ref config) = self.config {
            args.push(OsString::from("--config"));
            args.push(OsString::from(config.as_os_str()));
        }

        args.extend(self.remote_arguments().iter().map(OsString::from));
        args.extend(self.authentication_arguments());

        if let Some(ref ca) = self.ca {
            args.push(OsString::from("--ca"));
            args.push(OsString::from(ca.as_os_str()));
        }
        if let Some(ref crl) = self.crl {
            args.push(OsString::from("--crl-verify"));
            args.push(OsString::from(crl.as_os_str()));
        }

        if let Some((ref path, ref plugin_args)) = self.plugin {
            args.push(OsString::from("--plugin"));
            args.push(OsString::from(path));
            args.extend(plugin_args.iter().map(OsString::from));
        }

        if let Some(ref path) = self.log {
            args.push(OsString::from("--log"));
            args.push(OsString::from(path))
        }

        if let Some(mssfix) = self.tunnel_options.mssfix {
            args.push(OsString::from("--mssfix"));
            args.push(OsString::from(mssfix.to_string()));
        }

        if !self.enable_ipv6 {
            args.push(OsString::from("--pull-filter"));
            args.push(OsString::from("ignore"));
            args.push(OsString::from("route-ipv6"));

            args.push(OsString::from("--pull-filter"));
            args.push(OsString::from("ignore"));
            args.push(OsString::from("ifconfig-ipv6"));
        }

        if let Some(ref tunnel_device) = self.tunnel_alias {
            args.push(OsString::from("--dev-node"));
            args.push(tunnel_device.clone());
        }

        args.extend(Self::tls_cipher_arguments().iter().map(OsString::from));
        args.extend(self.proxy_arguments().iter().map(OsString::from));

        #[cfg(target_os = "linux")]
        if let Some(mark) = &self.fwmark {
            args.extend(["--mark", &mark.to_string()].iter().map(OsString::from));
        }

        args
    }

    fn base_arguments() -> Vec<&'static str> {
        let mut args = vec![];
        for arglist in BASE_ARGUMENTS.iter() {
            for arg in arglist.iter() {
                args.push(*arg);
            }
        }
        args
    }

    fn tls_cipher_arguments() -> Vec<String> {
        vec![
            "--tls-ciphersuites".to_owned(),
            ALLOWED_TLS1_3_CIPHERS.join(":"),
        ]
    }

    fn remote_arguments(&self) -> Vec<String> {
        let mut args: Vec<String> = vec![];
        if let Some(ref endpoint) = self.remote {
            args.push("--proto".to_owned());
            args.push(match endpoint.protocol {
                net::TransportProtocol::Udp => "udp".to_owned(),
                net::TransportProtocol::Tcp => "tcp-client".to_owned(),
            });
            args.push("--remote".to_owned());
            args.push(endpoint.address.ip().to_string());
            args.push(endpoint.address.port().to_string());
        }
        args
    }

    fn authentication_arguments(&self) -> Vec<OsString> {
        let mut args = vec![];
        if let Some(ref user_pass_path) = self.user_pass_path {
            args.push(OsString::from("--auth-user-pass"));
            args.push(OsString::from(user_pass_path));
        }
        args
    }

    fn proxy_arguments(&self) -> Vec<String> {
        let mut args = vec![];
        match self.proxy_settings {
            Some(CustomProxy::Socks5Local(ref local_proxy)) => {
                args.push("--socks-proxy".to_owned());
                args.push("127.0.0.1".to_owned());
                args.push(local_proxy.local_port.to_string());
                args.push("--route".to_owned());
                args.push(local_proxy.remote_endpoint.address.ip().to_string());
                args.push("255.255.255.255".to_owned());
                args.push("net_gateway".to_owned());
            }
            Some(CustomProxy::Socks5Remote(ref remote_proxy)) => {
                args.push("--socks-proxy".to_owned());
                args.push(remote_proxy.endpoint.ip().to_string());
                args.push(remote_proxy.endpoint.port().to_string());

                if let Some(ref _auth) = remote_proxy.auth {
                    if let Some(ref auth_file) = self.proxy_auth_path {
                        args.push(auth_file.to_string_lossy().to_string());
                    } else {
                        log::error!("Proxy credentials present but credentials file missing");
                    }
                }

                args.push("--route".to_owned());
                args.push(remote_proxy.endpoint.ip().to_string());
                args.push("255.255.255.255".to_owned());
                args.push("net_gateway".to_owned());
            }
            Some(CustomProxy::Shadowsocks(ref ss)) => {
                args.push("--socks-proxy".to_owned());
                args.push("127.0.0.1".to_owned());

                if let Some(ref proxy_port) = self.proxy_port {
                    args.push(proxy_port.to_string());
                } else {
                    panic!("Dynamic proxy port was not registered with OpenVpnCommand");
                }

                args.push("--route".to_owned());
                args.push(ss.endpoint.ip().to_string());
                args.push("255.255.255.255".to_owned());
                args.push("net_gateway".to_owned());
            }
            None => {}
        };
        args
    }
}

impl fmt::Display for OpenVpnCommand {
    /// Format the program and arguments of an `OpenVpnCommand` for display. Any non-utf8 data
    /// is lossily converted using the utf8 replacement character.
    fn fmt(&self, fmt: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt.write_str(&shell_escape::escape(self.openvpn_bin.to_string_lossy()))?;
        for arg in &self.get_arguments() {
            fmt.write_str(" ")?;
            fmt.write_str(&shell_escape::escape(arg.to_string_lossy()))?;
        }
        Ok(())
    }
}

/// Handle to a running OpenVPN process.
pub struct OpenVpnProcHandle {
    stop_tx: Option<oneshot::Sender<Duration>>,
    proc: tokio::task::JoinHandle<io::Result<std::process::ExitStatus>>,
}

impl OpenVpnProcHandle {
    /// Configures the expression to run OpenVPN in a way compatible with this handle
    /// and spawns it. Returns the handle.
    pub fn new(mut cmd: &mut tokio::process::Command) -> io::Result<Self> {
        use std::io::IsTerminal;

        if !std::io::stdout().is_terminal() {
            cmd = cmd.stdout(std::process::Stdio::null())
        }

        if !std::io::stderr().is_terminal() {
            cmd = cmd.stderr(std::process::Stdio::null())
        }

        let mut proc_handle = cmd.stdin(Stdio::piped()).spawn()?;

        let (stop_tx, mut stop_rx) = oneshot::channel();

        let proc = tokio::spawn(async move {
            let stdin = proc_handle.stdin.take().expect("expected stdin handle");

            tokio::select! {
                timeout = &mut stop_rx => {
                    // Dropping our stdin handle so that it is closed once. Closing the handle should
                    // gracefully stop our OpenVPN child process. This only works because our OpenVPN
                    // fork expects this.
                    drop(stdin);

                    if let Ok(timeout) = timeout {
                        //
                        // Controlled shutdown using nice_kill()
                        //

                        log::debug!("Trying to stop child process gracefully");

                        match tokio::time::timeout(timeout, proc_handle.wait()).await {
                            Ok(_) => log::debug!("Child process terminated gracefully"),
                            Err(_) => {
                                log::warn!(
                                    "Child process did not terminate gracefully within timeout, forcing termination"
                                );
                                proc_handle.kill().await?;
                            }
                        }
                    } else {
                        //
                        // If the abort channel is just dropped, kill the process immediately.
                        //
                        log::debug!("Killing OpenVPN process forcefully");
                        let _ = proc_handle.kill().await;
                    }

                    proc_handle.wait().await
                }

                //
                // If the process exits on its own, we're also done.
                //
                result = proc_handle.wait() => {
                    log::debug!("OpenVPN process terminated");
                    result
                }
            }
        });

        Ok(Self {
            stop_tx: Some(stop_tx),
            proc,
        })
    }

    /// Begins to kill the process, causing `wait()` to return. This function does not wait for the
    /// operation to complete.
    pub fn kill(&mut self, timeout: std::time::Duration) {
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(timeout);
        }
    }

    /// Waits for the child to exit completely.
    pub async fn wait(&mut self) -> io::Result<std::process::ExitStatus> {
        (&mut self.proc).await.expect("openvpn task panicked")
    }
}

#[cfg(test)]
mod tests {
    use super::OpenVpnCommand;
    use std::{ffi::OsString, net::Ipv4Addr};
    use talpid_types::net::{Endpoint, TransportProtocol};

    #[test]
    fn passes_one_remote() {
        let remote = Endpoint::new(Ipv4Addr::new(127, 0, 0, 1), 3333, TransportProtocol::Udp);

        let testee_args = OpenVpnCommand::new("").remote(remote).get_arguments();

        assert!(testee_args.contains(&OsString::from("udp")));
        assert!(testee_args.contains(&OsString::from("127.0.0.1")));
        assert!(testee_args.contains(&OsString::from("3333")));
    }

    #[test]
    fn passes_plugin_path() {
        let path = "./a/path";
        let testee_args = OpenVpnCommand::new("").plugin(path, vec![]).get_arguments();
        assert!(testee_args.contains(&OsString::from("./a/path")));
    }

    #[test]
    fn passes_plugin_args() {
        let args = vec![String::from("123"), String::from("cde")];
        let testee_args = OpenVpnCommand::new("").plugin("", args).get_arguments();
        assert!(testee_args.contains(&OsString::from("123")));
        assert!(testee_args.contains(&OsString::from("cde")));
    }
}
