{ pkgs ? import (fetchTarball "https://github.com/NixOS/nixpkgs/archive/360d84a26f0cfca4941d92a89e2b359d425a6d3d.tar.gz") {} }:
let
  lzy = ps: ps.callPackage ./lzy.nix {};
  python = pkgs.callPackage ./python.nix {};
  python-lzy = python.withPackages(ps: with ps; [
      (lzy ps)
  ]);
  mkEnv = custom-python: pkgs.callPackage ./mk-python-env.nix { custom-python = custom-python; };
in {
  shell = mkEnv python;
  shell-lzy = mkEnv python-lzy;
}
