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
            const rawTag = release.tag_name || 'v4.8.1';
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
