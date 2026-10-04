/**
 * JOYFLIX OFFICIAL WEBSITE - ULTRA-SMART JAVASCRIPT ENGINE
 * Handles dynamic GitHub releases, particle canvas, watch party simulator,
 * device switcher, QR modal, copy to clipboard, and FAQ accordions.
 */

document.addEventListener('DOMContentLoaded', () => {
    // 1. Current Year
    const yearSpan = document.getElementById('currentYear');
    if (yearSpan) yearSpan.textContent = new Date().getFullYear();

    // 2. Background Stardust Canvas
    initCinemaParticles();

    // 3. GitHub Latest Release Fetching
    fetchLatestGitHubRelease();

    // 4. Interactive Live Watch Party Simulator & Reaction Engine
    initPlayerSimulator();

    // 5. Device Experience Switcher (Phone vs TV)
    initDeviceSwitcher();

    // 6. QR Code Modal
    initQrModal();

    // 8. FAQ Accordion
    initFaqAccordion();

    // 9. Mobile Navigation Toggle
    initMobileNav();
});

/**
 * Background Stardust Cinema Canvas Particle Animation
 */
function initCinemaParticles() {
    const canvas = document.getElementById('cinemaCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let width = canvas.width = window.innerWidth;
    let height = canvas.height = window.innerHeight;

    window.addEventListener('resize', () => {
        width = canvas.width = window.innerWidth;
        height = canvas.height = window.innerHeight;
    });

    const particles = [];
    const count = Math.min(width > 768 ? 65 : 30, 80);

    for (let i = 0; i < count; i++) {
        particles.push({
            x: Math.random() * width,
            y: Math.random() * height,
            radius: Math.random() * 1.5 + 0.5,
            vx: (Math.random() - 0.5) * 0.3,
            vy: (Math.random() - 0.5) * 0.3,
            alpha: Math.random() * 0.5 + 0.1,
            color: Math.random() > 0.3 ? '229, 9, 20' : '88, 101, 242'
        });
    }

    function render() {
        ctx.clearRect(0, 0, width, height);

        particles.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;

            if (p.x < 0) p.x = width;
            if (p.x > width) p.x = 0;
            if (p.y < 0) p.y = height;
            if (p.y > height) p.y = 0;

            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${p.color}, ${p.alpha})`;
            ctx.fill();
        });

        requestAnimationFrame(render);
    }
    render();
}

/**
 * Fetches Latest Release from GitHub API
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
            const listRes = await fetch(fallbackReleasesUrl);
            if (listRes.ok) {
                const releasesList = await listRes.json();
                release = releasesList.find(r => !r.prerelease) || releasesList[0];
            }
        }

        if (release) {
            const rawTag = release.tag_name || 'v4.8.6';
            const cleanVersion = rawTag.replace(/^v/i, '');
            const displayTag = rawTag.startsWith('v') ? rawTag : `v${rawTag}`;

            // Find APK asset
            const apkAsset = release.assets && release.assets.find(asset => 
                asset.name.toLowerCase().endsWith('.apk')
            );

            let downloadUrl = release.html_url;
            let formattedSize = '73.7 MB';

            if (apkAsset) {
                downloadUrl = apkAsset.browser_download_url;
                if (apkAsset.size) {
                    const mb = (apkAsset.size / (1024 * 1024)).toFixed(1);
                    formattedSize = `${mb} MB`;
                }
            } else {
                downloadUrl = `https://github.com/${GITHUB_REPO}/releases/download/${displayTag}/JoyFlix-${cleanVersion}.apk`;
            }

            // Update UI elements
            if (primaryBtn) primaryBtn.href = downloadUrl;
            if (secondaryBtn) secondaryBtn.href = downloadUrl;
            if (pillVersion) pillVersion.textContent = cleanVersion;
            if (btnVersionBadge) btnVersionBadge.textContent = displayTag;
            if (btnSizeBadge) btnSizeBadge.textContent = formattedSize;

            dynVersions.forEach(span => {
                span.textContent = span.textContent.startsWith('v') ? displayTag : cleanVersion;
            });
        }
    } catch (e) {
        console.warn('Using default v4.8.6 release info:', e);
    }
}

/**
 * Interactive Live Watch Party Simulator
 */
function initPlayerSimulator() {
    const playBtn = document.getElementById('simulatorPlayBtn');
    const progressBar = document.getElementById('simulatedProgress');
    const timeDisplay = document.getElementById('simTimeCurrent');
    const chatFeed = document.getElementById('playerChatFeed');
    const reactionContainer = document.getElementById('reactionContainer');
    const reactionBtns = document.querySelectorAll('.reaction-btn');

    let isPlaying = true;
    let progressPercent = 52.4;
    let totalSeconds = (1 * 3600) + (42 * 60) + 15;

    // 1. Ticking Progress Simulator
    setInterval(() => {
        if (!isPlaying) return;
        progressPercent += 0.04;
        totalSeconds += 1;
        if (progressPercent > 100) progressPercent = 0;

        if (progressBar) progressBar.style.width = `${progressPercent}%`;

        if (timeDisplay) {
            const hrs = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
            const mins = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
            const secs = String(totalSeconds % 60).padStart(2, '0');
            timeDisplay.textContent = `${hrs}:${mins}:${secs}`;
        }
    }, 1000);

    // Play/Pause button
    if (playBtn) {
        playBtn.addEventListener('click', () => {
            isPlaying = !isPlaying;
            playBtn.innerHTML = isPlaying 
                ? '<svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16"></rect><rect x="14" y="4" width="4" height="16"></rect></svg>'
                : '<svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>';
            
            spawnChatToast('Host (Jehad)', isPlaying ? 'Resumed playback for everyone ▶' : 'Paused room ⏸');
        });
    }

    // 2. Simulated Chat Toasts
    const cannedMessages = [
        { sender: 'Sarah', text: 'Bro that wormhole CGI is breathtaking! 🚀' },
        { sender: 'Alex', text: 'Audio in Dolby 5.1 is insane 🔥' },
        { sender: 'Jehad (Host)', text: 'Synced 0ms lag on all devices ⚡' },
        { sender: 'Tanjim', text: 'Wait pause for snacks 🍿' },
        { sender: 'Sarah', text: 'Hans Zimmer music never fails 😭❤️' }
    ];

    let messageIndex = 0;
    setInterval(() => {
        if (!isPlaying || !chatFeed) return;
        const msg = cannedMessages[messageIndex % cannedMessages.length];
        messageIndex++;
        spawnChatToast(msg.sender, msg.text);
    }, 4500);

    function spawnChatToast(sender, text) {
        if (!chatFeed) return;
        const toast = document.createElement('div');
        toast.className = 'chat-toast';
        toast.innerHTML = `<span class="chat-sender">${sender}:</span> <span>${text}</span>`;
        chatFeed.appendChild(toast);

        // Keep maximum 2 toasts at a time
        while (chatFeed.children.length > 2) {
            chatFeed.removeChild(chatFeed.firstChild);
        }

        setTimeout(() => {
            if (toast.parentNode === chatFeed) {
                chatFeed.removeChild(toast);
            }
        }, 4200);
    }

    // 3. Clickable Reaction Emojis Burst
    reactionBtns.forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const emoji = btn.getAttribute('data-emoji') || '🔥';
            spawnFloatingEmoji(emoji);
        });
    });

    function spawnFloatingEmoji(emoji) {
        if (!reactionContainer) return;
        const span = document.createElement('span');
        span.className = 'floating-emoji';
        span.textContent = emoji;
        span.style.left = `${Math.random() * 30}px`;
        reactionContainer.appendChild(span);

        setTimeout(() => {
            if (span.parentNode === reactionContainer) {
                reactionContainer.removeChild(span);
            }
        }, 2200);
    }
}

/**
 * Dual Device Switcher (Mobile vs TV)
 */
function initDeviceSwitcher() {
    const tabs = document.querySelectorAll('.device-tab');
    const viewMobile = document.getElementById('viewMobile');
    const viewTv = document.getElementById('viewTv');

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');

            const device = tab.getAttribute('data-device');
            if (device === 'mobile') {
                viewMobile.classList.add('active');
                viewTv.classList.remove('active');
            } else {
                viewTv.classList.add('active');
                viewMobile.classList.remove('active');
            }
        });
    });
}


/**
 * Copy to Clipboard with Toast Notification
 */
window.copyText = function(inputId, btn) {
    const input = document.getElementById(inputId);
    if (!input) return;

    input.select();
    input.setSelectionRange(0, 99999);
    navigator.clipboard.writeText(input.value).then(() => {
        const originalText = btn.textContent;
        btn.textContent = 'Copied!';
        btn.style.background = '#22C55E';
        btn.style.color = '#000';

        showToast('Copied to clipboard!');

        setTimeout(() => {
            btn.textContent = originalText;
            btn.style.background = '';
            btn.style.color = '';
        }, 2000);
    }).catch(err => {
        console.error('Failed to copy text: ', err);
    });
};

function showToast(msg) {
    const toast = document.getElementById('toastNotify');
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('show');
    setTimeout(() => {
        toast.classList.remove('show');
    }, 2500);
}

/**
 * QR Code Modal
 */
function initQrModal() {
    const qrBtn = document.getElementById('qrBtn');
    const qrModal = document.getElementById('qrModal');
    const qrClose = document.getElementById('qrClose');

    if (!qrBtn || !qrModal) return;

    qrBtn.addEventListener('click', () => {
        qrModal.classList.add('active');
    });

    if (qrClose) {
        qrClose.addEventListener('click', () => {
            qrModal.classList.remove('active');
        });
    }

    qrModal.addEventListener('click', (e) => {
        if (e.target === qrModal) {
            qrModal.classList.remove('active');
        }
    });
}

/**
 * FAQ Accordion
 */
function initFaqAccordion() {
    const faqItems = document.querySelectorAll('.faq-item');

    faqItems.forEach(item => {
        const questionBtn = item.querySelector('.faq-question');
        const answer = item.querySelector('.faq-answer');

        if (questionBtn && answer) {
            questionBtn.addEventListener('click', () => {
                const isActive = item.classList.contains('active');

                // Close other open items
                faqItems.forEach(otherItem => {
                    otherItem.classList.remove('active');
                    const otherAnswer = otherItem.querySelector('.faq-answer');
                    if (otherAnswer) otherAnswer.style.maxHeight = null;
                });

                if (!isActive) {
                    item.classList.add('active');
                    answer.style.maxHeight = answer.scrollHeight + 'px';
                } else {
                    item.classList.remove('active');
                    answer.style.maxHeight = null;
                }
            });
        }
    });
}

/**
 * Mobile Navigation Menu Toggle
 */
function initMobileNav() {
    const menuToggle = document.getElementById('menuToggle');
    const navLinks = document.getElementById('navLinks');

    if (menuToggle && navLinks) {
        menuToggle.addEventListener('click', (e) => {
            e.stopPropagation();
            navLinks.classList.toggle('mobile-open');
        });

        // Close menu on click of any nav link
        navLinks.querySelectorAll('a').forEach(link => {
            link.addEventListener('click', () => {
                navLinks.classList.remove('mobile-open');
            });
        });

        // Close menu when clicking outside
        document.addEventListener('click', (e) => {
            if (!navLinks.contains(e.target) && !menuToggle.contains(e.target)) {
                navLinks.classList.remove('mobile-open');
            }
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

    if (!hasCode && !hasToken && !hasError && !pathname.includes('anilist') && !pathname.includes('mal') && !pathname.includes('simkl')) {
        return;
    }

    let serviceName = 'JoyFlix';
    let deepLink = '';

    // 1. AniList (Implicit token or code)
    if (hasToken || pathname.includes('anilist')) {
        serviceName = 'AniList';
        const params = hash ? ('?' + hash.replace(/^#\/?/, '')) : search;
        deepLink = `joyflixapp://anilistlogin${params}`;
    }
    // 2. MAL (MyAnimeList - state contains RequestID or mal in path)
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

    // Trigger instant deep link redirect
    try {
        window.location.href = deepLink;
    } catch (e) {
        console.warn('Auto redirect error:', e);
    }

    function renderModal() {
        if (document.getElementById('oauthBridgeModal')) return;

        const overlay = document.createElement('div');
        overlay.id = 'oauthBridgeModal';
        overlay.setAttribute('style', 'position: fixed; inset: 0; z-index: 999999; background: rgba(5, 7, 15, 0.95); backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); display: flex; align-items: center; justify-content: center; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;');
        
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
}

// Execute OAuth bridge immediately on script load
initOAuthBridge();
