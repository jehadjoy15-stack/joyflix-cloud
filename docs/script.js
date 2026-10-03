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

    // 6. Donation Tabs Switcher
    initDonationTabs();

    // 7. QR Code Modal
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
            const rawTag = release.tag_name || 'v4.8.5';
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
        console.warn('Using default v4.8.5 release info:', e);
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
 * Donation Tabs Switcher
 */
function initDonationTabs() {
    const tabs = document.querySelectorAll('.donation-tab');
    const panelCrypto = document.getElementById('donCrypto');
    const panelCoffee = document.getElementById('donCoffee');
    const panelLocal = document.getElementById('donLocal');

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');

            const target = tab.getAttribute('data-don-tab');
            if (panelCrypto) panelCrypto.classList.toggle('active', target === 'crypto');
            if (panelCoffee) panelCoffee.classList.toggle('active', target === 'coffee');
            if (panelLocal) panelLocal.classList.toggle('active', target === 'local');
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
        menuToggle.addEventListener('click', () => {
            const isOpen = navLinks.style.display === 'flex';
            navLinks.style.display = isOpen ? 'none' : 'flex';
            if (!isOpen) {
                navLinks.style.flexDirection = 'column';
                navLinks.style.position = 'absolute';
                navLinks.style.top = '76px';
                navLinks.style.left = '0';
                navLinks.style.right = '0';
                navLinks.style.background = 'rgba(7, 8, 12, 0.98)';
                navLinks.style.padding = '2rem 1.5rem';
                navLinks.style.borderBottom = '1px solid rgba(255, 255, 255, 0.1)';
            }
        });
    }
}
