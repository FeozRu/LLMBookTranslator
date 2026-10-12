#!/usr/bin/env bash
# Запуск переводчика книг (Linux/macOS). JDK 17+ нужен в PATH (на NixOS — из nixpkgs).
cd "$(dirname "$0")"
./gradlew :desktop:run
