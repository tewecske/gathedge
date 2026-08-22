# NixOS module for gathedge.
#
# Deliberately does NOT enable services.postgresql or services.nginx — it only contributes
# a database, a role and a vhost to whatever the host already runs. That way a second app
# on the same machine merges in without conflict: one Postgres cluster with many databases,
# one nginx with many vhosts.
{ config, lib, pkgs, ... }:
let
  cfg = config.services.gathedge;
  inherit (lib) mkIf mkOption mkEnableOption mkBefore types;
in
{
  options.services.gathedge = {
    enable = mkEnableOption "the gathedge application";

    package = mkOption {
      type = types.package;
      default = pkgs.gathedge-backend;
      defaultText = lib.literalExpression "pkgs.gathedge-backend";
      description = "Backend package providing bin/gathedge-backend.";
    };

    webPackage = mkOption {
      type = types.package;
      default = pkgs.gathedge-web;
      defaultText = lib.literalExpression "pkgs.gathedge-web";
      description = "Built SPA assets served by nginx as the vhost root.";
    };

    hostName = mkOption {
      type = types.str;
      example = "gathedge.lan";
      description = ''
        nginx virtual host name. It is also set as the default vhost, so reaching the
        server by bare IP works too.
      '';
    };

    publicBaseUrl = mkOption {
      type = types.str;
      example = "http://gathedge.lan";
      description = ''
        Origin the app builds user-visible links from (the email confirmation link, the Google
        OAuth redirect). Must match how users actually reach the server, or those links
        will point somewhere unreachable.
      '';
    };

    port = mkOption {
      type = types.port;
      default = 8080;
      description = ''
        Loopback port the backend binds. Not exposed: nginx proxies /api to it, which is
        what keeps the SPA same-origin with the API (the X-Requested-With CSRF check
        depends on that, since the backend runs no CORS middleware).
      '';
    };

    openFirewall = mkOption {
      type = types.bool;
      default = true;
      description = "Open the HTTP port in the firewall.";
    };

    production = mkOption {
      type = types.bool;
      default = false;
      description = ''
        Sets APP_ENV=production and SESSION_COOKIE_SECURE=true.

        AppConfig.productionIssues then refuses the boot unless publicBaseUrl is https:// and
        the DB_PASSWORD in the environment file differs from the development default — i.e. it
        assumes a TLS terminator in front of nginx.

        Leave it off for the first boot: AdminSeeder does not run in production, so switching
        it on before an administrator exists leaves the deployment with no account at all. Seed
        with it off, sign in, change the password, then switch it on.

        A Secure cookie is never sent over plain HTTP, so once this is on the app can only be
        signed into over the https origin — reaching it by bare IP will load the SPA and fail
        to authenticate.
      '';
    };

    trustedProxyHops = mkOption {
      type = types.ints.unsigned;
      default = 1;
      description = ''
        How many reverse proxies stand between the browser and the backend. The address is
        taken that many entries in from the RIGHT of X-Forwarded-For, because each proxy
        appends and only the right-hand end is unforgeable.

        1 is this module's nginx alone. 2 adds one further hop in front of it — a Cloudflare
        tunnel, a CDN, an ingress.

        Both wrong answers are security bugs. Too low and every request carries nginx's own
        address, so AuthService rate-limits the whole deployment as one client: five failed
        sign-ins from anybody block sign-in, sign-up and verification resends for every
        account. Too high and an entry of an attacker-supplied header is treated as the
        client address.
      '';
    };

    captcha = {
      siteKey = mkOption {
        type = types.str;
        default = "";
        example = "0x4AAAAAAARU4P2weUyGOcKw";
        description = ''
          Cloudflare Turnstile site key, the public half of the pair. The browser renders the
          widget with it, so it is not a secret and may live in the Nix store. Leave empty to
          switch captcha off everywhere (the guarded endpoints then skip verification, which is
          how the module boots with no Turnstile account at all).

          The matching secret must go in `environmentFile` as CAPTCHA_SECRET — never here. The
          app only turns captcha on when both halves are set.
        '';
      };

      loginThreshold = mkOption {
        type = types.ints.unsigned;
        default = 2;
        description = ''
          How many failed sign-in attempts one client address may make before the sign-in form
          demands a captcha. Sits below the rate limiter's hard lockout, so a human gets a
          challenge before a bot is hard-blocked.
        '';
      };
    };

    environmentFile = mkOption {
      type = types.path;
      example = "/var/lib/secrets/gathedge.env";
      description = ''
        systemd EnvironmentFile holding the secrets, kept out of the Nix store (the store
        is world-readable). Must define DB_PASSWORD; should define BOOTSTRAP_ADMIN_EMAIL
        and BOOTSTRAP_ADMIN_PASSWORD; may define GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
        and GOOGLE_REDIRECT_URI; may define CAPTCHA_SECRET (Cloudflare Turnstile — the
        server-only half whose public partner is services.gathedge.captcha.siteKey).

        Mode 0400, owned by root. DB_PASSWORD is also read by the gathedge-db-password
        unit, which sets the Postgres role's password to match.
      '';
    };

    database = {
      name = mkOption {
        type = types.str;
        default = "gathedge";
        description = "Database created in the host's Postgres cluster.";
      };
      user = mkOption {
        type = types.str;
        default = "gathedge";
        description = "Postgres role owning that database.";
      };
    };

    backup = {
      enable = mkEnableOption "daily database backups to Google Drive";

      rcloneConfigFile = mkOption {
        type = types.path;
        example = "/var/lib/secrets/rclone.conf";
        description = ''
          rclone config holding a `gdrive` remote pointing at the Google Drive target.
          Generate it once with `rclone config` (an interactive OAuth flow) and keep it
          out of the Nix store. Mode 0400, owned by root.
        '';
      };

      remoteDir = mkOption {
        type = types.str;
        default = "gdrive:backups/gathedge";
        example = "gdrive:backups/gathedge";
        description = ''
          rclone target directory. The service writes one gzip'd dump per run named
          `gathedge-YYYY-MM-DD.sql.gz` and prunes files older than `retentionDays`.
        '';
      };

      retentionDays = mkOption {
        type = types.ints.positive;
        default = 7;
        description = "How many days of backups to keep on the remote.";
      };

      time = mkOption {
        type = types.str;
        default = "02:00";
        example = "06:30";
        description = "Local time (HH:MM) the daily backup runs.";
      };
    };
  };

  config = mkIf cfg.enable {

    # Additive only — the host turns the cluster on.
    services.postgresql = {
      ensureDatabases = [ cfg.database.name ];
      ensureUsers = [{
        name = cfg.database.user;
        ensureDBOwnership = true;
      }];
      # pgjdbc speaks TCP only (no unix socket without junixsocket), so the backend
      # connects over loopback with a password. mkBefore matters: the NixOS defaults are
      # themselves mkAfter and start with blanket `trust` lines, which would match first.
      authentication = mkBefore ''
        host ${cfg.database.name} ${cfg.database.user} 127.0.0.1/32 scram-sha-256
        host ${cfg.database.name} ${cfg.database.user} ::1/128      scram-sha-256
      '';
    };

    # services.postgresql.ensureUsers cannot set a password (the option was removed from
    # nixpkgs), so set it here from the same secret file the backend reads. psql's :'var'
    # interpolation quotes the value properly, so a password with a quote in it is safe.
    #
    # The statement is piped in rather than passed with `-c`, and that is load-bearing:
    # variable interpolation is something psql's own lexer does to input it parses, while a
    # `-c` string is handed to the server as-is. With `-c` the server receives the literal
    # `:'pw'` and answers `ERROR: syntax error at or near ":"`.
    systemd.services.gathedge-db-password = {
      description = "Set the gathedge Postgres role password";
      after = [ "postgresql.service" "postgresql-setup.service" ];
      requires = [ "postgresql.service" ];
      wantedBy = [ "multi-user.target" ];
      before = [ "gathedge-backend.service" ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "postgres";
        Group = "postgres";
        LoadCredential = "env:${toString cfg.environmentFile}";
      };
      script = ''
        set -euo pipefail
        pw=$(${pkgs.gnugrep}/bin/grep -m1 '^DB_PASSWORD=' "$CREDENTIALS_DIRECTORY/env" | cut -d= -f2-)
        if [ -z "$pw" ]; then
          echo "DB_PASSWORD is empty or absent in the environment file; refusing to set an empty role password" >&2
          exit 1
        fi
        printf '%s\n' "ALTER ROLE ${cfg.database.user} WITH LOGIN PASSWORD :'pw'" \
          | ${config.services.postgresql.package}/bin/psql \
              --no-psqlrc --quiet --tuples-only \
              -v ON_ERROR_STOP=1 \
              -v pw="$pw"
      '';
    };

    systemd.services.gathedge-backend = {
      description = "gathedge backend (ZIO HTTP)";
      after = [ "network.target" "postgresql.service" "gathedge-db-password.service" ];
      requires = [ "postgresql.service" ];
      wantedBy = [ "multi-user.target" ];

      # Every name here is a ${?VAR} override in the backend's application.conf. Typesafe
      # Config substitution reads real environment variables, which is what systemd's
      # Environment=/EnvironmentFile= provide.
      environment = {
        # Both of these follow `production`, which is off by default: while the app is served
        # over plain HTTP, AppConfig.productionIssues would refuse the boot. See that option's
        # description for what switching it on requires.
        APP_ENV = if cfg.production then "production" else "dev";
        SESSION_COOKIE_SECURE = lib.boolToString cfg.production;
        SERVER_HOST = "127.0.0.1";
        SERVER_PORT = toString cfg.port;
        DB_URL = "jdbc:postgresql://127.0.0.1:${toString config.services.postgresql.settings.port}/${cfg.database.name}";
        DB_USER = cfg.database.user;
        PUBLIC_BASE_URL = cfg.publicBaseUrl;
        # Without this the backend takes application.conf's default of 0 — the socket peer,
        # which behind the nginx below is always 127.0.0.1 for every client at once.
        TRUSTED_PROXY_HOPS = toString cfg.trustedProxyHops;
        CAPTCHA_SITE_KEY = cfg.captcha.siteKey;
        CAPTCHA_LOGIN_THRESHOLD = toString cfg.captcha.loginThreshold;
        NETTY_MAX_THREADS = "0";
        JAVA_OPTS = "-XX:MaxRAMPercentage=75";
      };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        EnvironmentFile = cfg.environmentFile;

        # Flyway migrates before Server.serve and fails fast if Postgres is not reachable,
        # so a restart loop is the boot-race handling — same reasoning as the compose
        # file's `restart: unless-stopped`.
        Restart = "on-failure";
        RestartSec = "5s";

        # No state on disk: sessions and all data live in Postgres.
        DynamicUser = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        NoNewPrivileges = true;
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
        RestrictNamespaces = true;
        LockPersonality = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectControlGroups = true;
      };
    };

    # The dictionary loader (bin/gathedge-dictionary-import, built alongside bin/gathedge-backend
    # in the same package — see nix/scala.nix) is run by hand, not by a systemd unit, so nothing
    # else puts it on PATH or gives it the DB_* variables the backend's own unit gets from
    # `environment`/`EnvironmentFile` above. This wraps it with both, the same way, so an operator
    # can just run `gathedge-dictionary-import --seed <path>` after a DB reset.
    environment.systemPackages = [
      (pkgs.writeShellScriptBin "gathedge-dictionary-import" ''
        set -euo pipefail
        if [ "$(id -u)" -ne 0 ]; then
          echo "gathedge-dictionary-import must run as root (reads DB_PASSWORD from ${toString cfg.environmentFile})" >&2
          exit 1
        fi
        set -a
        source ${toString cfg.environmentFile}
        set +a
        export DB_URL="jdbc:postgresql://127.0.0.1:${toString config.services.postgresql.settings.port}/${cfg.database.name}"
        export DB_USER="${cfg.database.user}"
        exec ${cfg.package}/bin/gathedge-dictionary-import "$@"
      '')
    ];

    # Daily pg_dump of the gathedge database pushed to Google Drive via rclone, keeping
    # `retentionDays` of history. Runs as the postgres user (so pg_dump reaches the cluster)
    # and connects over loopback with the same password the backend uses. The dump and the
    # rclone config are both credentials, so both come from LoadCredential — never the store.
    systemd.services.gathedge-backup = mkIf cfg.backup.enable {
      description = "Daily gathedge database backup to Google Drive";
      after = [ "postgresql.service" "gathedge-db-password.service" "network-online.target" ];
      requires = [ "postgresql.service" "network-online.target" ];
      wants = [ "network-online.target" ];

      serviceConfig = {
        Type = "oneshot";
        User = "postgres";
        Group = "postgres";
        LoadCredential = [
          "env:${toString cfg.environmentFile}"
          "rclone:${toString cfg.backup.rcloneConfigFile}"
        ];
      };

      script = ''
        set -euo pipefail
        pw=$(${pkgs.gnugrep}/bin/grep -m1 '^DB_PASSWORD=' "$CREDENTIALS_DIRECTORY/env" | cut -d= -f2-)

        export PGHOST=127.0.0.1
        export PGPORT=${toString config.services.postgresql.settings.port}
        export PGDATABASE=${cfg.database.name}
        export PGUSER=${cfg.database.user}
        export PGPASSWORD="$pw"

        stamp=$(date +%Y-%m-%d)
        dump="/tmp/gathedge-$stamp.sql.gz"

        ${config.services.postgresql.package}/bin/pg_dump --no-psqlrc --clean --if-exists --format=plain \
          | ${pkgs.gzip}/bin/gzip -9 > "$dump"

        ${pkgs.rclone}/bin/rclone --config "$CREDENTIALS_DIRECTORY/rclone" \
          copy "$dump" "${cfg.backup.remoteDir}/"
        rm -f "$dump"

        ${pkgs.rclone}/bin/rclone --config "$CREDENTIALS_DIRECTORY/rclone" \
          delete "${cfg.backup.remoteDir}/" --min-age ${toString cfg.backup.retentionDays}d
      '';
    };

    systemd.timers.gathedge-backup = mkIf cfg.backup.enable {
      description = "Daily gathedge database backup timer";
      wantedBy = [ "timers.target" ];
      timerConfig = {
        OnCalendar = "*-*-* ${cfg.backup.time}:00";
        Persistent = true;
        RandomizedDelaySec = "15min";
      };
    };

    # Translation of docker/nginx.conf.
    services.nginx = {
      recommendedGzipSettings = lib.mkDefault true;
      recommendedProxySettings = lib.mkDefault true;
      clientMaxBodySize = lib.mkDefault "1m";

      virtualHosts.${cfg.hostName} = {
        default = true;
        root = "${cfg.webPackage}";

        # The OAuth callback carries a bearer credential in its URL: the authorization code,
        # as a query parameter. RouteSupport.loggableUrl keeps it out of the backend's own
        # request log; nginx writes $request verbatim and has to be told separately, or the
        # access log keeps the copy the backend was careful not to make. A regex location
        # outranks a prefix one, so this wins over "/api/" below.
        locations."~ ^/api/auth/[^/]+/callback" = {
          proxyPass = "http://127.0.0.1:${toString cfg.port}";
          extraConfig = "access_log off;";
        };

        # No trailing path, so the /api prefix is preserved — backend routes are mounted
        # under /api.
        locations."/api/".proxyPass = "http://127.0.0.1:${toString cfg.port}";

        locations."/assets/".extraConfig = ''
          add_header Cache-Control "public, immutable, max-age=31536000";
          try_files $uri =404;
        '';

        # Fallback to index.html is what makes Waypoint's HTML5 history routing survive a
        # reload on a deep link.
        locations."/".extraConfig = ''
          add_header Cache-Control "no-cache";
          try_files $uri $uri/ /index.html;
        '';
      };
    };

    networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [ 80 ];

    assertions = [
      {
        assertion = !cfg.backup.enable || cfg.backup.rcloneConfigFile != null;
        message = "services.gathedge.backup.enable needs backup.rcloneConfigFile set.";
      }
      {
        assertion = config.services.postgresql.enable;
        message = "services.gathedge needs services.postgresql.enable = true on the host.";
      }
      {
        assertion = config.services.nginx.enable;
        message = "services.gathedge needs services.nginx.enable = true on the host.";
      }
      # The backend checks this too and fails at boot; catching it during evaluation says so
      # before a rebuild swaps in a unit that cannot start.
      {
        assertion = !cfg.production || lib.hasPrefix "https://" cfg.publicBaseUrl;
        message =
          "services.gathedge.production requires an https:// publicBaseUrl "
          + "(it is currently '${cfg.publicBaseUrl}'); the backend refuses to start otherwise.";
      }
    ];
  };
}
