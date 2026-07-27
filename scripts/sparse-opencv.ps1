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
    git sparse-checkout set modules include 3rdparty cmake platforms
    git checkout -- .
    Write-Host "OpenCV sparse checkout applied under $Oc"
    $size = (Get-ChildItem -Recurse -File -ErrorAction SilentlyContinue |
        Measure-Object Length -Sum).Sum / 1MB
    Write-Host ("Approx worktree size: {0:N0} MB" -f $size)
} finally {
    Pop-Location
}
