// ---- Company detail page ----

async function getJson(url) {
    const res = await fetch(url);
    if (!res.ok) {
        const err = new Error(url + ' → ' + res.status);
        err.status = res.status;
        throw err;
    }
    return res.json();
}

function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function daysAgoLabel(days) {
    if (days === null || days === undefined) return 'No coverage';
    if (days === 0) return 'Today';
    if (days === 1) return 'Yesterday';
    return days + ' days ago';
}

// Turns the raw enum value (e.g. "NO_COVERAGE") into a display label ("No Coverage").
// The raw value is still used as-is for the badge's CSS class (see styles.css).
function statusLabel(status) {
    if (!status) return '';
    return status.toLowerCase().split('_')
        .map(w => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');
}

function renderHeader(status) {
    document.title = `${status.name} — Portfolio Press Monitor`;
    document.getElementById('companyName').textContent = status.name;
    document.getElementById('companySub').textContent = status.statusDescription || '';

    const badge = document.getElementById('statusBadge');
    badge.textContent = statusLabel(status.status);
    badge.className = 'badge ' + status.status;
}

function renderCards(status) {
    const b = status.breakdown;
    const cards = [
        { value: status.mentionsInQuarter, label: 'Total mentions (quarter)' },
        { value: b.positive, label: 'Positive (quarter)', cls: 'pos' },
        { value: b.negative, label: 'Negative (quarter)', cls: 'neg' },
        { value: daysAgoLabel(status.daysSinceLastMention), label: 'Last mentioned' },
    ];
    document.getElementById('cards').innerHTML = cards.map(c => `
        <div class="card">
            <div class="value ${c.cls || ''}">${c.value}</div>
            <div class="label">${c.label}</div>
        </div>`).join('');
}

function renderMentions(mentions) {
    const el = document.getElementById('mentions');
    if (mentions.length === 0) {
        el.innerHTML = '<p class="muted">No mentions collected yet.</p>';
        return;
    }
    el.innerHTML = mentions.map(m => `
        <div class="mention-item">
            <span class="dot ${m.sentiment}"></span>
            <a href="${m.url}" target="_blank" rel="noopener noreferrer">${m.title}</a>
            <div class="meta">${m.source || 'unknown'} · ${fmtDate(m.publishedAt)} · <b>${m.sentiment}</b></div>
            ${m.reason ? `<div class="reason">“${m.reason}”</div>` : ''}
        </div>`).join('');
}

function showError(message) {
    document.getElementById('companyName').textContent = 'Company not found';
    document.getElementById('companySub').textContent = message;
    document.getElementById('cards').innerHTML = '';
    document.getElementById('mentions').innerHTML = '';
}

async function load() {
    const id = new URLSearchParams(location.search).get('id');
    if (!id) {
        showError('No company was specified.');
        return;
    }
    try {
        const [status, mentions] = await Promise.all([
            getJson(`/api/companies/${encodeURIComponent(id)}`),
            getJson(`/api/companies/${encodeURIComponent(id)}/mentions`),
        ]);
        renderHeader(status);
        renderCards(status);
        renderMentions(mentions);
    } catch (e) {
        showError(e.status === 404
            ? 'No company with this ID was found.'
            : e.message);
    }
}

load();
