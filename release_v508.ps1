$ErrorActionPreference = "Stop"

$srcApk = "app\build\outputs\apk\stable\release\app-stable-release.apk"
$destApk = "JoyFlix-v5.0.8.apk"
Write-Host "Copying APK to $destApk..."
Copy-Item $srcApk $destApk -Force

Write-Host "Committing git changes..."
git add -A
git commit -m "feat: JoyFlix v5.0.8 - Watch Together Guest Auto-Play, Live Stream URL Broadcast, Voice Status Sync, Scrollable Dialog & Portrait Pause Scale"
git tag -a v5.0.8 -m "Release JoyFlix v5.0.8 (Build 85)"

Write-Host "Pushing git commits and tag..."
git push origin main
git push origin v5.0.8

Write-Host "Retrieving GitHub credential..."
$credInput = "protocol=https`nhost=github.com`n"
$cred = $credInput | git credential fill
$token = ($cred -split "`n" | Where-Object { $_ -like "password=*" }).Substring(9).Trim()

$repo = "jehadjoy15-stack/joyflix-cloud"
$tagName = "v5.0.8"
$releaseName = "JoyFlix v5.0.8"
$apkSizeMB = [math]::Round((Get-Item $destApk).Length / 1MB, 2)

$body = @"
## JoyFlix v5.0.8 (Build 85)

### What's New & Fixed:
- **Watch Together Guest Auto-Play & Video Fix**:
  - Fixed issue where guests joining a room would not have the video start playing. Directly launches player via `ACTION_PLAY_EPISODE_IN_PLAYER`.
  - Fixed season and episode matching logic when joining from Watch Together.
  - Resolved `getCurrentMediaUrl()` fallback to ensure room media URLs are never blank.
  - Auto-seeks and triggers play synchronization immediately when guest player loads stream dimensions.
- **Live Stream URL & Source Broadcast (Lite App Support)**:
  - Broadcasts extracted video stream link (`streamUrl`), page link (`mediaUrl`), and provider name (`apiName`) directly to `joycall` server.
  - Allows third-party players and lite apps to stream directly from the room source.
- **Live Voice Status Sync**:
  - Live microphone activation is now reported directly to the server via `/api/rooms/:roomId/voice-status`.
  - Displays `voiceActive: true` and active voice users in real time.
- **Scrollable Watch Together Menu**:
  - Wrapped room dialog in a smooth `NestedScrollView` so room codes and buttons are easily accessible on all phone screen sizes.
- **Portrait Pause Logo & Metadata Polish**:
  - Scaled down the oversized pause logo (48dp height) and overview text in 16:9 portrait mode while keeping full cinematic size in landscape.

### Download:
- APK: [JoyFlix-v5.0.8.apk](https://github.com/jehadjoy15-stack/joyflix-cloud/releases/download/v5.0.8/JoyFlix-v5.0.8.apk)
"@

$releasePayload = @{
    tag_name = $tagName
    target_commitish = "main"
    name = $releaseName
    body = $body
    draft = $false
    prerelease = $false
} | ConvertTo-Json

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/vnd.github.v3+json"
    "User-Agent" = "PowerShell-JoyFlix-Release"
}

Write-Host "Creating GitHub release $tagName..."
$releaseResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Method Post -Body $releasePayload -Headers $headers -ContentType "application/json; charset=utf-8"
$releaseId = $releaseResponse.id
Write-Host "Release created with ID: $releaseId"

$uploadUri = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$destApk"
Write-Host "Uploading asset $destApk ($apkSizeMB MB)..."
$uploadHeaders = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/vnd.android.package-archive"
    "User-Agent" = "PowerShell-JoyFlix-Release"
}

$uploadResponse = Invoke-RestMethod -Uri $uploadUri -Method Post -InFile $destApk -Headers $uploadHeaders
Write-Host "Asset uploaded successfully! Download URL: $($uploadResponse.browser_download_url)"

Remove-Item $destApk -Force -ErrorAction SilentlyContinue
