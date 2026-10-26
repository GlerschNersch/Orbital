Set-Location C:\Users\matt\WatchReborn
$files = @(
  "app\src\main\java\dev\watchreborn\MainActivity.kt",
  "app\src\main\java\dev\watchreborn\ui\WatchView.kt",
  "app\src\main\java\dev\watchreborn\ui\Face.kt",
  "app\src\main\java\dev\watchreborn\ui\NavBack.kt",
  "app\src\main\java\dev\watchreborn\ui\WatchAccessibility.kt",
  "app\src\test\java\dev\watchreborn\ui\NavBackTest.kt",
  "app\build.gradle.kts",
  "CHANGELOG.md"
)
foreach ($f in $files) {
  $h = Get-FileHash -Algorithm MD5 $f
  Write-Output ($h.Hash.ToLower() + "  " + $f)
}
Write-Output ("Stack.kt exists: " + (Test-Path "app\src\main\java\dev\watchreborn\ui\Stack.kt"))
& .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain *> C:\Users\matt\build210.log
Write-Output ("EXIT=" + $LASTEXITCODE)
Get-Content C:\Users\matt\build210.log -Tail 8
$total = 0; $fail = 0; $err = 0
Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object {
  [xml]$x = Get-Content $_.FullName
  $total += [int]$x.testsuite.tests; $fail += [int]$x.testsuite.failures; $err += [int]$x.testsuite.errors
}
Write-Output ("TESTS total=" + $total + " failures=" + $fail + " errors=" + $err)
