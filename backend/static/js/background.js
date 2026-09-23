/* =========================================================================
 * KARM CRED - ambient "trust network" background.
 *
 * Many small, honest signals (dots) drifting and linking to their nearest
 * neighbours when close: the product's mental model (independent gig income
 * streams adding up to a connected picture of trust) rendered as texture.
 *
 * Constraints (P0 spec):
 *   - single hand-rolled canvas, no libraries, ~150 lines
 *   - 40-60 nodes (fewer on small screens), accent #38bdf8 at <= 0.15 alpha
 *   - ~30fps cap via timestamp check inside the RAF loop
 *   - pauses entirely when the tab is hidden, and yields during scrolling
 *   - prefers-reduced-motion -> one static frame, no animation
 *   - O(n^2) neighbour lines skipped entirely below 480px viewport width
 * ========================================================================= */
(function () {
    'use strict';

    var canvas = document.getElementById('bg-network');
    if (!canvas || !canvas.getContext) return;
    var ctx = canvas.getContext('2d');
    var reduced = window.matchMedia &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    var nodes = [];
    var w = 0, h = 0;
    var rafId = null, lastFrame = 0, lastScroll = 0, running = false;
    var LINK_DIST = 150;   // px neighbour threshold
    var MAX_LINKS = 3;     // nearest 2-3 neighbours per node

    function nodeCount() {
        if (w < 480) return 28;   // small phones: fewer dots, no lines
        if (w < 900) return 40;
        return 56;                // cap stays under the 60 budget
    }

    function seed() {
        var n = nodeCount();
        nodes = [];
        for (var i = 0; i < n; i++) {
            nodes.push({
                x: Math.random() * w,
                y: Math.random() * h,
                vx: (Math.random() - 0.5) * 0.45,
                vy: (Math.random() - 0.5) * 0.45,
                r: 1.2 + Math.random() * 1.6
            });
        }
    }

    function resize() {
        var dpr = Math.min(window.devicePixelRatio || 1, 2); // cap DPR=2
        w = window.innerWidth;
        h = window.innerHeight;
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        seed();
        if (reduced) draw(true);   // static frame only
    }

    function step() {
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            n.x += n.vx;
            n.y += n.vy;
            if (n.x < -10) n.x = w + 10; else if (n.x > w + 10) n.x = -10;
            if (n.y < -10) n.y = h + 10; else if (n.y > h + 10) n.y = -10;
        }
    }

    function draw(staticFrame) {
        ctx.clearRect(0, 0, w, h);
        var i, j, a, b, dx, dy, d;

        // neighbour lines - O(n^2) with per-node cap, skipped on <480px
        if (w >= 480) {
            var links = new Array(nodes.length);
            for (i = 0; i < nodes.length; i++) links[i] = 0;
            ctx.lineWidth = 1;
            for (i = 0; i < nodes.length; i++) {
                if (links[i] >= MAX_LINKS) continue;
                a = nodes[i];
                for (j = i + 1; j < nodes.length; j++) {
                    if (links[j] >= MAX_LINKS) continue;
                    b = nodes[j];
                    dx = a.x - b.x;
                    dy = a.y - b.y;
                    d = Math.sqrt(dx * dx + dy * dy);
                    if (d > LINK_DIST) continue;
                    links[i]++; links[j]++;
                    var alpha = (1 - d / LINK_DIST) * 0.15; // <= 0.15
                    ctx.strokeStyle = 'rgba(56,189,248,' + alpha.toFixed(3) + ')';
                    ctx.beginPath();
                    ctx.moveTo(a.x, a.y);
                    ctx.lineTo(b.x, b.y);
                    ctx.stroke();
                    if (links[i] >= MAX_LINKS) break;
                }
            }
        }

        // dots ("workers")
        ctx.fillStyle = 'rgba(56,189,248,0.15)';
        for (i = 0; i < nodes.length; i++) {
            a = nodes[i];
            ctx.beginPath();
            ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2);
            ctx.fill();
        }
        if (staticFrame) { /* nothing - frozen on purpose */ }
    }

    function loop(ts) {
        rafId = window.requestAnimationFrame(loop);
        if (ts - lastFrame < 33) return;          // ~30fps cap
        lastFrame = ts;
        if (document.hidden) return;              // tab hidden: cost nothing
        if (ts - lastScroll < 220) return;        // yield while user scrolls
        step();
        draw(false);
    }

    function start() {
        if (reduced || running || document.hidden) return;
        running = true;
        lastFrame = 0;
        rafId = window.requestAnimationFrame(loop);
    }

    function stop() {
        running = false;
        if (rafId) window.cancelAnimationFrame(rafId);
        rafId = null;
    }

    var resizeTimer = null;
    window.addEventListener('resize', function () {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(resize, 150);
    });
    window.addEventListener('scroll', function () {
        lastScroll = performance.now();
    }, { passive: true });
    document.addEventListener('visibilitychange', function () {
        if (document.hidden) stop(); else start();
    });

    resize();
    if (!reduced) start();
})();
