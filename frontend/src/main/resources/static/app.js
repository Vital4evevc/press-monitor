// ---- OurCrowd Press Monitor dashboard ----
let allCompanies = [];

async function getJson(url, opts) {
    const res = await fetch(url, opts);
    if (!res.ok) throw new Error(url + ' → ' + res.status);
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

// ---------- Summary cards ----------
function renderCards(s) {
    const b = s.breakdown;
    const cards = [
        { value: s.totalCompanies, label: 'Companies tracked' },
        { value: s.totalMentionsInQuarter, label: 'Total mentions (quarter)' },
        { value: b.positive, label: 'Positive', cls: 'pos' },
        { value: b.negative, label: 'Negative', cls: 'neg' },
    ];
    document.getElementById('cards').innerHTML = cards.map(c => `
        <div class="card">
            <div class="value ${c.cls || ''}">${c.value}</div>
            <div class="label">${c.label}</div>
        </div>`).join('');
}

// ---------- Company table ----------

// lastMentionedAt as a timestamp, or null when there's no usable date. Companies with no
// coverage at all have no date, and neither does one we somehow failed to parse — both are
// "unknown" rather than "old", which is why the sort below pushes them to the bottom.
function lastMentionedTime(c) {
    if (!c.lastMentionedAt) return null;
    const t = Date.parse(c.lastMentionedAt);
    return Number.isNaN(t) ? null : t;
}

// Each sort is just a numeric key plus a way to spot rows that have no value for it. Keeping
// the direction out of these means "ascending" is one sign flip rather than a second set of
// comparators to keep in step.
const SORTS = {
    // The order the API already returns, so this is the no-op default.
    mentions: {
        value: c => c.mentionsInQuarter,
        missing: () => false,
    },
    recent: {
        value: c => lastMentionedTime(c),
        missing: c => lastMentionedTime(c) === null,
    },
};

function comparator(key, dir) {
    const sort = SORTS[key] || SORTS.mentions;
    const sign = dir === 'asc' ? -1 : 1;
    return (a, b) => {
        // Companies with nothing to sort on sink to the bottom in BOTH directions. A company
        // that has never been in the news isn't "the oldest" — flipping to ascending should
        // surface the least recently covered company that actually has coverage, not park 200
        // blank rows at the top.
        const aMissing = sort.missing(a);
        const bMissing = sort.missing(b);
        if (aMissing && bMissing) return a.name.localeCompare(b.name);
        if (aMissing) return 1;
        if (bMissing) return -1;

        const diff = sort.value(b) - sort.value(a);
        // Ties always fall back to A-Z, regardless of direction.
        return diff === 0 ? a.name.localeCompare(b.name) : diff * sign;
    };
}

function sentiBar(b) {
    const total = b.positive + b.negative + b.neutral + b.unknown;
    if (total === 0) return '<span class="muted">—</span>';
    const pct = n => (n / total * 100).toFixed(1) + '%';
    return `<span class="senti-bar" title="+${b.positive} / ~${b.neutral} / -${b.negative}">
        <span class="p" style="width:${pct(b.positive)}"></span>
        <span class="u" style="width:${pct(b.neutral + b.unknown)}"></span>
        <span class="n" style="width:${pct(b.negative)}"></span>
    </span>`;
}

function renderTable() {
    const q = document.getElementById('search').value.toLowerCase();
    const statusFilter = document.getElementById('statusFilter').value;
    const onlyCovered = document.getElementById('onlyCovered').checked;

    // filter() hands back a new array, so sorting it in place leaves allCompanies untouched.
    const rows = allCompanies.filter(c => {
        if (q && !c.name.toLowerCase().includes(q)) return false;
        if (statusFilter && c.status !== statusFilter) return false;
        if (onlyCovered && c.mentionsInQuarter === 0) return false;
        return true;
    });
    rows.sort(comparator(
        document.getElementById('sortBy').value,
        document.getElementById('sortDir').value));

    document.querySelector('#companyTable tbody').innerHTML = rows.map(c => `
        <tr data-id="${c.id}">
            <td class="company-name">${c.name}</td>
            <td class="num">${c.mentionsInQuarter}</td>
            <td>${sentiBar(c.breakdown)}</td>
            <td>${c.lastMentionedAt ? fmtDate(c.lastMentionedAt) + ' · ' + daysAgoLabel(c.daysSinceLastMention) : '—'}</td>
            <td><span class="badge ${c.status}" title="${c.statusDescription}">${statusLabel(c.status)}</span></td>
        </tr>`).join('');

    document.querySelectorAll('#companyTable tbody tr').forEach(tr =>
        tr.addEventListener('click', () => {
            location.href = `company.html?id=${encodeURIComponent(tr.dataset.id)}`;
        }));
}

// ---------- Latest mentions feed ----------
function renderFeed(mentions) {
    const panel = document.getElementById('feedPanel');
    panel.classList.toggle('hidden', mentions.length === 0);
    if (mentions.length === 0) {
        return;
    }
    document.getElementById('feed').innerHTML = mentions.slice(0, 25).map(m => `
        <li>
            <span class="dot ${m.sentiment}"></span>
            <div>
                <a href="${m.url}" target="_blank" rel="noopener noreferrer">${m.title}</a>
                <div class="meta">${m.companyName} · ${m.source || 'unknown'} · ${fmtDate(m.publishedAt)}</div>
            </div>
        </li>`).join('');
}

// ---------- Run ----------
async function triggerRun() {
    const btn = document.getElementById('runBtn');
    btn.disabled = true;
    btn.textContent = 'Running…';
    try {
        const res = await fetch('/api/run', { method: 'POST' });
        const data = await res.json();
        if (res.status === 503) { alert(data.message); return resetRunBtn(); }
        if (res.status === 409) { alert('A run is already in progress.'); }
        pollRun();
    } catch (e) {
        alert('Failed to start run: ' + e.message);
        resetRunBtn();
    }
}

function resetRunBtn() {
    const btn = document.getElementById('runBtn');
    btn.disabled = false;
    btn.textContent = 'Run now';
}

async function pollRun() {
    try {
        const s = await getJson('/api/run/status');
        if (s.running) {
            setTimeout(pollRun, 3000);
        } else {
            resetRunBtn();
            await loadAll();
            if (s.lastRun) {
                alert(`Run complete — ${s.lastRun.newMentions} new mention(s) from ${s.lastRun.companiesScanned} companies.`);
            }
        }
    } catch {
        setTimeout(pollRun, 4000);
    }
}

// ---------- Bootstrap ----------
async function loadAll() {
    const [summary, companies, recent] = await Promise.all([
        getJson('/api/summary'),
        getJson('/api/companies'),
        getJson('/api/mentions/recent?limit=25'),
    ]);
    allCompanies = companies;
    renderCards(summary);
    renderTable();
    renderFeed(recent);
}

document.getElementById('search').addEventListener('input', renderTable);
document.getElementById('statusFilter').addEventListener('change', renderTable);
document.getElementById('sortBy').addEventListener('change', renderTable);
document.getElementById('sortDir').addEventListener('change', renderTable);
document.getElementById('onlyCovered').addEventListener('change', renderTable);
document.getElementById('runBtn').addEventListener('click', triggerRun);

loadAll().catch(e => {
    document.getElementById('cards').innerHTML =
        `<div class="card"><div class="value neg">!</div><div class="label">Failed to load data: ${e.message}</div></div>`;
});
