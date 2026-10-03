/**
 * JOYFLIX OFFICIAL WEBSITE - JAVASCRIPT ENGINE
 * Handles dynamic GitHub release fetching, FAQ interactions, and mobile navigation.
 */

document.addEventListener('DOMContentLoaded', () => {
    // 1. Set current year in footer
    const yearSpan = document.getElementById('currentYear');
    if (yearSpan) {
        yearSpan.textContent = new Date().getFullYear();
    }

    // 2. Fetch Latest Release from GitHub API
    fetchLatestGitHubRelease();

    // 3. Initialize FAQ Accordion
    initFaqAccordion();

    // 4. Initialize Mobile Navigation
    initMobileNav();
});

/**
 * Fetches the latest JoyFlix APK release dynamically from GitHub
 */
async function fetchLatestGitHubRelease() {
    const GITHUB_REPO = 'jehadjoy15-stack/joyflix-cloud';
    const apiUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;
    const fallbackReleasesUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases`;

    const primaryBtn = document.getElementById('primaryDownloadBtn');
    const secondaryBtn = document.getElementById('secondaryDownloadBtn');
    const pillVersion = document.getElementById('pillVersion');
    const btnVersionBadge = document.getElementById('btnVersionBadge');
    const btnSizeBadge = document.getElementById('btnSizeBadge');
    const dynVersions = document.querySelectorAll('.dyn-version');

    try {
        let release = null;
        let response = await fetch(apiUrl);
        
        if (response.ok) {
            release = await response.json();
        } else {
            // Fallback to list of releases if latest endpoint fails or 404
            const listRes = await fetch(fallbackReleasesUrl);
            if (listRes.ok) {
                const releasesList = await listRes.json();
                release = releasesList.find(r => !r.prerelease) || releasesList[0];
            }
        }

        if (release) {
            const rawTag = release.tag_name || 'v4.8.4';
            const cleanVersion = rawTag.replace(/^v/i, '');
            const displayTag = rawTag.startsWith('v') ? rawTag : `v${rawTag}`;

            // Find APK asset
            const apkAsset = release.assets && release.assets.find(asset => 
                asset.name.toLowerCase().endsWith('.apk')
            );

            // Update version badges
            if (pillVersion) pillVersion.textContent = cleanVersion;
            if (btnVersionBadge) btnVersionBadge.textContent = displayTag;
            dynVersions.forEach(el => el.textContent = cleanVersion);

            // Update file size if available
            if (apkAsset && apkAsset.size && btnSizeBadge) {
                const sizeInMb = (apkAsset.size / (1024 * 1024)).toFixed(1);
                btnSizeBadge.textContent = `${sizeInMb} MB`;
            }

            // Update download button URLs with direct APK link
            if (apkAsset && apkAsset.browser_download_url) {
                if (primaryBtn) primaryBtn.href = apkAsset.browser_download_url;
                if (secondaryBtn) secondaryBtn.href = apkAsset.browser_download_url;
            }
        }
    } catch (err) {
        console.warn('Could not fetch latest release dynamically, using default fallback.', err);
    }
}

/**
 * Initializes interactive accordion behavior for the FAQ section
 */
function initFaqAccordion() {
    const faqItems = document.querySelectorAll('.faq-item');

    faqItems.forEach(item => {
        const questionBtn = item.querySelector('.faq-question');
        const answer = item.querySelector('.faq-answer');

        questionBtn.addEventListener('click', () => {
            const isActive = item.classList.contains('active');

            // Close all other items
            faqItems.forEach(otherItem => {
                otherItem.classList.remove('active');
                const otherAnswer = otherItem.querySelector('.faq-answer');
                if (otherAnswer) {
                    otherAnswer.style.maxHeight = null;
                }
            });

            // Toggle current item
            if (!isActive) {
                item.classList.add('active');
                answer.style.maxHeight = answer.scrollHeight + 'px';
            } else {
                item.classList.remove('active');
                answer.style.maxHeight = null;
            }
        });
    });
}

/**
 * Handles mobile hamburger toggle and auto-close on link click
 */
function initMobileNav() {
    const menuToggle = document.getElementById('menuToggle');
    const navLinks = document.getElementById('navLinks');

    if (menuToggle && navLinks) {
        menuToggle.addEventListener('click', () => {
            navLinks.classList.toggle('active');
        });

        // Close menu on navigation link click
        navLinks.querySelectorAll('a').forEach(link => {
            link.addEventListener('click', () => {
                navLinks.classList.remove('active');
            });
        });
    }
}

/**
 * JoyFlix OAuth Bridge
 * Automatically intercepts MAL, AniList, and Simkl OAuth redirects and passes
 * tokens/codes back to the JoyFlix Android App via joyflixapp:// custom deep link.
 */
function initOAuthBridge() {
    const search = window.location.search || '';
    const hash = window.location.hash || '';
    const pathname = window.location.pathname.toLowerCase();

    const hasCode = search.includes('code=');
    const hasToken = hash.includes('access_token=') || search.includes('access_token=');
    const hasError = search.includes('error=') || hash.includes('error=');

    if (!hasCode && !hasToken && !hasError) {
        return;
    }

    let serviceName = 'JoyFlix';
    let deepLink = '';

    // 1. AniList (Implicit token or code)
    if (hasToken || pathname.includes('anilist')) {
        serviceName = 'AniList';
        const params = hash ? hash : search;
        deepLink = `joyflixapp://anilistlogin${params}`;
    }
    // 2. MAL (MyAnimeList - state contains RequestID)
    else if (search.includes('RequestID') || pathname.includes('mal')) {
        serviceName = 'MyAnimeList (MAL)';
        deepLink = `joyflixapp://mallogin${search}`;
    }
    // 3. Simkl (code & state or simkl in path)
    else if (pathname.includes('simkl') || hasCode) {
        serviceName = 'Simkl';
        deepLink = `joyflixapp://simkllogin${search}`;
    }

    if (!deepLink) return;

    function renderModal() {
        if (document.getElementById('oauthBridgeModal')) return;

        const overlay = document.createElement('div');
        overlay.id = 'oauthBridgeModal';
        overlay.setAttribute('style', 'position: fixed; inset: 0; z-index: 999999; background: rgba(5, 7, 15, 0.95); backdrop-filter: blur(12px); display: flex; align-items: center; justify-content: center; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;');
        
        overlay.innerHTML = `
            <div style="background: #111422; border: 1px solid rgba(229, 9, 20, 0.4); box-shadow: 0 20px 50px rgba(0,0,0,0.85), 0 0 35px rgba(229, 9, 20, 0.25); border-radius: 20px; max-width: 440px; width: 100%; padding: 32px; text-align: center; color: #fff;">
                <div style="width: 68px; height: 68px; margin: 0 auto 20px; background: rgba(229,9,20,0.15); border-radius: 50%; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(229,9,20,0.4);">
                    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#e50914" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"></path>
                        <polyline points="10 17 15 12 10 7"></polyline>
                        <line x1="15" y1="12" x2="3" y2="12"></line>
                    </svg>
                </div>
                <h3 style="font-size: 22px; font-weight: 700; margin-bottom: 8px; color: #fff;">Connecting to JoyFlix</h3>
                <p style="font-size: 14px; color: #9ca3af; line-height: 1.6; margin-bottom: 24px;">
                    Authorizing with <strong style="color: #fff;">${serviceName}</strong>. Opening the JoyFlix app to complete your login...
                </p>
                <a id="oauthLaunchBtn" href="${deepLink}" style="display: block; width: 100%; padding: 14px 20px; background: linear-gradient(135deg, #e50914 0%, #b80610 100%); color: #fff; font-size: 15px; font-weight: 600; text-decoration: none; border-radius: 12px; box-shadow: 0 4px 15px rgba(229,9,20,0.4); margin-bottom: 12px; box-sizing: border-box;">
                    🚀 Open JoyFlix App
                </a>
                <button id="oauthCancelBtn" style="background: transparent; border: none; color: #6b7280; font-size: 13px; cursor: pointer; padding: 6px 12px;">
                    Stay on Website
                </button>
            </div>
        `;

        document.body.appendChild(overlay);

        const cancelBtn = document.getElementById('oauthCancelBtn');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', () => {
                overlay.remove();
            });
        }
    }

    if (document.body) {
        renderModal();
    } else {
        document.addEventListener('DOMContentLoaded', renderModal);
    }

    // Trigger deep link
    try {
        window.location.href = deepLink;
    } catch (e) {
        console.warn('Auto redirect error:', e);
    }
}

// Initialize OAuth bridge immediately
initOAuthBridge();
