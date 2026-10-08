$ErrorActionPreference = "Stop"

$srcApk = "app\build\outputs\apk\stable\release\app-stable-release.apk"
$destApk = "JoyFlix-v5.0.7.apk"
Write-Host "Copying APK to $destApk..."
Copy-Item $srcApk $destApk -Force

Write-Host "Committing git changes..."
git add -A
git commit -m "feat: JoyFlix v5.0.7 - Live Voice Call, Fullscreen Chat Sidebar, Player Controls & Icon Polish"
git tag -a v5.0.7 -m "Release JoyFlix v5.0.7 (Build 84)"

Write-Host "Pushing git commits and tag..."
git push origin main
git push origin v5.0.7

Write-Host "Retrieving GitHub credential..."
$credInput = "protocol=https`nhost=github.com`n"
$cred = $credInput | git credential fill
$token = ($cred -split "`n" | Where-Object { $_ -like "password=*" }).Substring(9).Trim()

$repo = "jehadjoy15-stack/joyflix-cloud"
$tagName = "v5.0.7"
$releaseName = "JoyFlix v5.0.7"
$apkSizeMB = [math]::Round((Get-Item $destApk).Length / 1MB, 2)

$body = @"
## JoyFlix v5.0.7 (Build 84)

### What's New & Fixed:
- **Watch Together Live Voice Call**:
  - Fully restored and enabled live voice calls across the bottom control bar, chat sidebar header, floating draggable mic button, and Watch Party dialog.
  - Real-time audio ducking: movie audio automatically ducks when other party members speak.
- **Fullscreen Chat Sidebar Drawer**:
  - Redesigned fullscreen chat as a sleek right drawer with room status, message history, quick reaction emojis (🔥 😂 ❤️ 😱 👏 🍿 👍 🎉), and responsive input.
  - YouTube-style portrait mode with bottom tabs for episodes and live room chat.
- **Player Controls & Navigation Polish**:
  - Removed the screen lock button to streamline navigation.
  - Moved **Next Episode** and **Skip OP** to the very front of the control bar for instant access without horizontal scrolling.
  - Fixed the Play/Pause button vector animation seam/slit, restoring a clean, solid icon.
  - Preserved Cloudflare / Dorabash captcha bypass and enlarged TMDB pause screen logo.

### Download:
- APK: [JoyFlix-v5.0.7.apk](https://github.com/jehadjoy15-stack/joyflix-cloud/releases/download/v5.0.7/JoyFlix-v5.0.7.apk)
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
