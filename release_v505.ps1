$ErrorActionPreference = "Stop"

$srcApk = "app\build\outputs\apk\stable\release\app-stable-release.apk"
$destApk = "JoyFlix-v5.0.5.apk"
Write-Host "Copying APK to $destApk..."
Copy-Item $srcApk $destApk -Force

Write-Host "Committing git changes..."
git add -A
git commit -m "feat: JoyFlix v5.0.5 - Fix movie and anime sync: initialize provider ID prefixes and expand Simkl/AniList support"
git tag -a v5.0.5 -m "Release JoyFlix v5.0.5 (Build 82)"

Write-Host "Pushing git commits and tag..."
git push origin main
git push origin v5.0.5

Write-Host "Retrieving GitHub credential..."
$credInput = "protocol=https`nhost=github.com`n"
$cred = $credInput | git credential fill
$token = ($cred -split "`n" | Where-Object { $_ -like "password=*" }).Substring(9).Trim()

$repo = "jehadjoy15-stack/joyflix-cloud"
$tagName = "v5.0.5"
$releaseName = "JoyFlix v5.0.5"
$apkSizeMB = [math]::Round((Get-Item $destApk).Length / 1MB, 2)

$body = @"
## JoyFlix v5.0.5 (Build 82)

### What's Fixed:
- **Cloud Sync ID Initialization**:
  - Fixed empty sync prefixes in LoadResponse (malIdPrefix, simklIdPrefix, aniListIdPrefix) so items properly resolve and sync to remote providers instead of defaulting to local storage.
  - Guaranteed initialization via AccountManager.initMainAPI() during application and activity startup.
- **TMDb ID & Simkl Mapping**:
  - Added full TMDb mapping support for movies and series syncing to Simkl.
  - Added direct fallback resolution for IMDb and TMDb IDs.
- **AniList Sync Resilience**:
  - Increased GraphQL API timeout to 30s to eliminate timeouts and failed status updates on mobile networks.

### Download:
- APK: [JoyFlix-v5.0.5.apk](https://github.com/jehadjoy15-stack/joyflix-cloud/releases/download/v5.0.5/JoyFlix-v5.0.5.apk)
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
