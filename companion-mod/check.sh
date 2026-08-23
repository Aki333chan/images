#!/usr/bin/env bash
# Всё, что можно проверить без файлов игры.
#
# Сюда НЕ входит сборка Aurum.Companion.Game против настоящей игры: для неё
# нужны сборки с игрового сервера, см. README.md. Вместо неё игровой слой
# собирается против заглушек — это ловит опечатки и неверные вызовы, но не
# доказывает совместимость с игрой.
set -euo pipefail
cd "$(dirname "$0")"

echo "== Ядро =="
dotnet build src/Aurum.Companion.Core/Aurum.Companion.Core.csproj -v q --nologo

echo "== Игровой слой против заглушек =="
dotnet build tests/Aurum.Companion.Game.StubCheck/Aurum.Companion.Game.StubCheck.csproj -v q --nologo

echo "== Тесты ядра =="
dotnet test tests/Aurum.Companion.Core.Tests/Aurum.Companion.Core.Tests.csproj -v q --nologo
