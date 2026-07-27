# Drop unused OpenCV trees from the submodule worktree (doc/samples/data/apps).
# Run from repo root after: git submodule update --init --recursive
# Cone mode keeps root files (CMakeLists.txt, LICENSE, …) automatically —
# only list directories to include.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Oc = Join-Path $Root "app\src\main\cpp\third_party\opencv"
if (-not (Test-Path $Oc)) {
    Write-Error "OpenCV submodule not checked out at $Oc"
}
Push-Location $Oc
try {
    git sparse-checkout init --cone
    # Include `hal` — Carotene (NEON) lives there; OpenCV CMake also probes
    # KleidiCV under hal/kleidicv when WITH_KLEIDICV is on.
    git sparse-checkout set modules include 3rdparty cmake platforms hal
    git checkout -- .
    # OpenCV CMake always add_subdirectory(doc/data); those trees are huge and
    # unused — plant no-op stubs so configure succeeds without checking them out.
    foreach ($stub in @("doc", "data")) {
        $dir = Join-Path $Oc $stub
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Set-Content -Path (Join-Path $dir "CMakeLists.txt") `
            -Value "# Auto-generated stub for sparse OpenCV checkout`n"
    }
    Write-Host "OpenCV sparse checkout applied under $Oc"
    $size = (Get-ChildItem -Recurse -File -ErrorAction SilentlyContinue |
        Measure-Object Length -Sum).Sum / 1MB
    Write-Host ("Approx worktree size: {0:N0} MB" -f $size)
} finally {
    Pop-Location
}
