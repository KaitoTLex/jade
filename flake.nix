{
  description = "HDL project";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # ichika.url = "github:kaitotlex/ichika";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
      # ichika,
    }:
    let
      javaVersion = 21;

      overlay =
        final: prev:
        let
          jdk = prev."jdk${toString javaVersion}";
        in
        {
          sbt = prev.sbt.override { jre = jdk; };
          scala = prev.scala_3.override { jre = jdk; };
          mill = prev.mill.override { jre = jdk; };
        };
    in
    {
      overlays.default = overlay;
    }
    // flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ overlay ];
        };
        # hdlApps = ichika.lib.makeHdlApps {
        #   inherit pkgs;
        #   top = "my_top";
        #   part = "xczu3eg-sfvc784-1-e";
        #   rtlDirs = [ "rtl" ];
        #   serverLocal = "10.0.0.228";
        #   serverUser = "vivado"; # set to your SSH user on the build server
        #   # serverDns        = "build.example.com";
        #   # sshKey           = "~/.ssh/id_ed25519";
        #   # constraintsFiles = [ "timing.xdc" "pins.xdc" ];
        #   # implTcl          = ./custom_impl.tcl;
        #   # implTclArgs      = [ "/remote/path/constraints.xdc" ];
        # };
      in
      {
        # apps = hdlApps;
        devShells.default = pkgs.mkShellNoCC {
          packages = with pkgs; [
            scala
            sbt
            mill
            coursier
            verilator
            gcc
            gnumake
            python3
            jdk21
            which
          ];
        };
      }
    );
}
