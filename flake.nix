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
      inputs,
      # ichika,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        javaVersion = 23; # Change this value to update the whole stack

        supportedSystems = [
          "x86_64-linux"
          "aarch64-linux"
          "x86_64-darwin"
          "aarch64-darwin"
        ];
        forEachSupportedSystem =
          f:
          inputs.nixpkgs.lib.genAttrs supportedSystems (
            system:
            f {
              pkgs = import inputs.nixpkgs {
                inherit system;
                overlays = [ inputs.self.overlays.default ];
              };
            }
          );
        pkgs = nixpkgs.legacyPackages.${system};
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
        overlays.default =
          final: prev:
          let
            jdk = prev."jdk${toString javaVersion}";
          in
          {
            sbt = prev.sbt.override { jre = jdk; };
            scala = prev.scala_3.override { jre = jdk; };
            mill = prev.mill.override { jre = jdk; };
          };
        devShells = forEachSupportedSystem (
          { pkgs }:
          {
            default = pkgs.mkShellNoCC {
              packages = with pkgs; [
                scala
                sbt
                mill
                coursier
                verilator
                gcc
                gnumake
                python3
                jdk23
                which
              ];
            };
          }
        );
      }
    );
}
