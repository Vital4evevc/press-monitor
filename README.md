# OurCrowd Portfolio Press Monitor

Tracks news coverage of OurCrowd portfolio and fund companies, works out whether each mention
is good or bad news for that company using a language model running on your own machine, and
puts the result on a dashboard. A scheduled job checks for new coverage once a day and fires a
webhook when it finds any.

It answers three questions:

1. What press did each company get this quarter, and was it positive, negative or neutral?
2. How long has it been since each company was last in the news? Fresh within 3 days, Recent
   within 45, Cooling within 90, Dormant beyond that, and No Coverage for companies nothing has
   ever been found for. Dormant and No Coverage are kept apart on purpose — "went quiet" and
   "never found anything" call for very different follow-up, and lumping them together hides
   which one you're looking at.
3. Has anything new turned up since yesterday, and can I be told about it automatically?

No cloud LLM is involved at any point. Classification runs against a local Ollama server.

---

## Getting it running

### What you need

**[Docker Desktop](https://docs.docker.com/get-docker/)** on Windows or macOS, or Docker Engine
with the Compose plugin on Linux. It has to be actually running, not just installed — `run.sh`
checks for that, because "Docker is installed but the daemon isn't up" is the single most common
way this fails to start.

That really is the only dependency. You do **not** need Java or Maven locally: both services are
built inside the containers using a `maven:3.9-eclipse-temurin-17` build stage, so your machine
never needs a JDK. Same for MySQL and Ollama — nothing gets installed on the host.

On Windows you'll also want **Git Bash or WSL**, because `run.sh` and `seed-db.sh` are shell
scripts and won't run from CMD or PowerShell. If you'd rather not, skip `run.sh` and use
`docker compose up --build` directly; it does the same thing.

Give Docker some room. This stack runs a language model on CPU alongside MySQL and two JVMs, so
budget roughly **8 GB of memory** and **8 GB of free disk** — the model alone is about 2.5 GB,
and the images add several more. Docker Desktop caps how much of your machine it will use, under
Settings, Resources; if Ollama keeps dying or classification is glacially slow, that limit is the
first thing to check. A GPU is optional and everything works without one, just slower.

### Starting it

```bash
./run.sh
```

That checks Docker is installed and running, then hands over to `docker compose up --build`.
The first run pulls the MySQL and Ollama images plus the model, so give it a few minutes.

When it settles, open **http://localhost:8080**.

### Filling the database (optional)

**Open a bash shell in the `data` directory and run the seed script:**

```bash
cd data
./seed-db.sh
```

This creates the tables, loads all 258 companies, and restores the mention snapshot if one
exists. On Windows use Git Bash or WSL, since it's a bash script.

You may not need it. Docker Compose mounts `data/mysql-init` into the MySQL container, and
MySQL runs everything in there by itself the first time it starts against an empty volume. So a
genuinely fresh stack arrives already populated.

The catch is that MySQL only does this once, ever, for a given volume. If the volume already
exists — which it does the moment you've started the stack once — those files are ignored
completely. Change a seed file, restart, and nothing happens. That's the gap `seed-db.sh`
fills: it applies the same files to a database that's already there. Everything it runs is
written to be safe to repeat, so running it when you didn't need to costs you nothing.

If the dashboard is empty and you're not sure why, this is the first thing to try.

### Getting some actual news in there

The backend deliberately doesn't start collecting on boot, because a full pass over 258
companies against a CPU model takes a while. Kick one off when you're ready:

```bash
curl -X POST http://localhost:8080/api/run
curl http://localhost:8080/api/run/status
```

After that it runs itself daily at 07:00.

---

## How it's put together

Three separate Maven projects. Not modules of a parent — three independent builds, each with
its own `pom.xml`, deployed separately.

```
press-monitor/
├── shared-library/   the Company and Mention entities, and the Sentiment enum. That's all.
├── backend/          collects news, classifies it, writes to MySQL. No UI. Port 8081.
├── frontend/         serves the dashboard and its API. Port 8080. The only public one.
├── data/             seed-db.sh and the SQL files that build the database
├── backups/          timestamped dumps from seed-db.sh --export (not committed)
├── docker-compose.yml
└── run.sh
```

The browser only ever talks to the frontend. The frontend reads MySQL directly for everything
it displays, using its own read-only database user, and only calls the backend for the two
things it genuinely can't do itself: starting a collection run, and asking whether Ollama is
alive.

```
  browser  ──►  frontend :8080  ──────────────►  MySQL   ◄──────────  backend :8081
                (dashboard,                   (read-only            (collect, classify,
                 reads MySQL directly)         for frontend)         read/write)
                       │                                                   ▲
                       └── /api/run, /api/health ──────────────────────────┘
                                                                     Google News RSS
                                                                     Ollama (local)
```

### Why shared-library is so small

It holds the two JPA entities and one enum, because those are the only things both services
genuinely need — the backend writes those rows, the frontend reads them, and they have to agree
on the shape.

Everything else stayed where it's used. The repositories are *not* shared: each service has its
own `CompanyRepository` and `MentionRepository`, trimmed to the queries it actually makes. The
backend's `MentionRepository` has one custom method, for checking whether an article is already
stored. The frontend's has a different one, for reading a company's mentions back out. They're
one-line interfaces, and duplicating them is cheaper than coupling both services to a shared
library release every time one of them needs a new query.

The dashboard code — `DashboardService`, `DashboardController`, the DTOs, `MentionStatus` —
lives only in the frontend. It used to be shared, which meant Spring's component scan quietly
registered those REST endpoints in the backend too, where nothing ever called them.

### What each service is made of

```
shared-library/
└── model/            Company, Mention (JPA entities), Sentiment

backend/
├── collector/        NewsCollector, GoogleNewsRssCollector, NewsItem
├── llm/              OllamaClient, SentimentClassifier, SentimentResult
├── service/          MonitoringService (the pipeline), CompanySeedLoader, AlertService,
│                     PipelineRunner, RunResult
├── scheduler/        DailyMonitorJob, AutowiringSpringBeanJobFactory
├── repository/       its own CompanyRepository, MentionRepository
├── config/           Ollama/Monitoring/Alert properties, QuartzConfig, AppConfig
├── web/              RunController, HealthController
└── resources/        application.yml, companies.csv (the 258-company seed list)

frontend/
├── service/          DashboardService (quarter aggregates, status buckets)
├── web/              DashboardController, RunProxyController, HealthProxyController
│   └── dto/          CompanyStatusDto, DashboardSummary, MentionDto, SentimentBreakdown,
│                     TimelinePoint
├── model/            MentionStatus
├── repository/       its own CompanyRepository, MentionRepository
├── config/           BackendProperties, AppConfig
└── resources/static/ index.html, app.js, company.html, company.js, styles.css

data/
├── seed-db.sh
└── mysql-init/       01 read-only user, 02 Quartz tables, 03 schema + companies,
                      04 mention snapshot (generated, see below)
```

---

## The pipeline

`MonitoringService` walks the company list, six at a time. For each company it asks Google News
RSS for recent articles, throws away anything older than the quarter, anything it has already
stored, and anything duplicated within the same feed. Whatever survives goes to the model.

The model gets one prompt per article that asks two things at once — is this actually about the
company we mean, and is the coverage good or bad for them — which halves the number of round
trips. Relevant results get written to MySQL. `PipelineRunner` ties collection and alerting
together, and both the daily job and the manual trigger go through it.

### Alerts

When a run turns up new coverage, `AlertService` POSTs a JSON summary — how many mentions, for
which companies, with their sentiment and links — to whatever `ALERT_WEBHOOK_URL` points at.
Nothing is written to disk; the mentions themselves are in MySQL, and the webhook is purely the
notification.

Out of the box it points at a [webhook.site](https://webhook.site) test inbox, which is a
throwaway endpoint that captures whatever you POST to it and shows it in a browser. That means
you can watch the alerts arrive without setting up anything of your own:

**https://webhook.site/#!/view/cbba35a3-f77a-42e2-bc62-671c4e2a08eb**

To send them somewhere else, change `ALERT_WEBHOOK_URL` in `docker-compose.yml`:

```yaml
  backend:
    environment:
      ALERT_WEBHOOK_URL: "https://your-endpoint.example.com/hook"
```

Or leave the file alone and set it in the environment, since Compose passes the host value
through when there is one:

```bash
ALERT_WEBHOOK_URL=https://your-endpoint.example.com/hook docker compose up
```

Two things worth knowing about that default. It's a **public inbox** — the URL is in this repo,
so anyone reading it can watch the alerts, which is fine for a demo and not fine for anything
real. And webhook.site inboxes expire after a period of inactivity, so if alerts seem to vanish,
check the inbox still exists before assuming the pipeline broke.

There's no default in the Java code deliberately: `alert.webhook-url` is `@NotBlank`, so if you
clear it the backend refuses to start rather than quietly sending real company data somewhere
you didn't intend.

### Deciding an article is irrelevant

Company names collide. Stripe, Shield, Near, Wave, Peak — all real portfolio companies, all
words that turn up constantly in unrelated news. There are three defences: the search query
quotes the company name, `companies.csv` has an optional `search_hint` column for the worst
offenders, and the model is asked to judge relevance.

An article is only dropped when a plain string check for the company name fails *and* the model
says it's irrelevant. Either signal on its own is too weak to delete real coverage on. The name
check misses articles that refer to a company obliquely, and a small local model will
occasionally decide a perfectly on-topic article isn't.

### Scheduling

Quartz, not Spring's `@Scheduled`, with its state persisted in MySQL rather than held in
memory. That matters for one specific reason: if the backend is down at 07:00 — a deploy, a
crash, the container not up yet — an in-memory scheduler simply forgets the fire ever happened.
With state in the database, the missed run is still on the books when the service returns, and
it runs immediately instead of waiting another day.

The QRTZ_* tables come from `data/mysql-init/02-quartz-schema.sql`. Don't be tempted to let
Spring create them by setting `initialize-schema: always` — the script Quartz ships starts with
`DROP TABLE`, so it would wipe scheduler state on every boot and undo the whole point.

---

## The local model

Everything runs through a local Ollama server. Default model is **`qwen3:4b`**.

The thing that actually constrains this choice isn't intelligence, it's throughput. A full run
is roughly 3,900 classification calls, and Compose runs Ollama on CPU unless you hand it a GPU,
so every extra billion parameters is paid for 3,900 times. Judging sentiment from a headline is
an easy task. Where a better model genuinely helps is telling apart the companies with ambiguous
names, and the string check already backstops that. A 4B model sits at a sensible point.

Going bigger has a real cost. On CPU a 7B roughly halves throughput, and a 14B can blow through
the 60-second timeout — at which point that article is silently stored as `UNKNOWN` rather than
failing loudly. Only reach for a bigger model if you've attached a GPU.

Calls are made with `format: "json"` so output is constrained to valid JSON, and temperature 0
so results are repeatable.

One trap worth knowing about: qwen3 is a reasoning model, and by default it writes out its
thinking before answering. That fights with the JSON constraint and produces replies with no
usable sentiment in them, which then land in the database as `UNKNOWN` while the run reports
success. So `ollama.think` is set to `false`. If you swap to a model that doesn't understand
that flag, the client notices and retries without it.

Every stored mention keeps the model's one-line reason for its verdict. That's there so you can
sanity-check quality quickly — pull up twenty mentions, read the reasons, and see whether
"raises $50M" came out positive and "faces lawsuit" came out negative.

---

## Where the data lives

MySQL, and only MySQL. There's no export step, no `data/` folder full of JSON, nothing to
regenerate. The dashboard queries the database live on every request.

Two database users. The backend connects with full read/write access and owns the schema. The
frontend connects as `pressmonitor_ro`, which can only run `SELECT`. Both read the same tables
through the same entity classes.

### Keeping your mentions across a rebuild

Companies come from a CSV in the repo, so they can always be rebuilt. Mentions can't — they're
whatever Google News happened to return and whatever the model made of it, and collecting them
again means paying for the whole LLM run a second time. `docker compose down -v` would throw
them away.

So there's a way to snapshot them:

```bash
cd data
./seed-db.sh --dump-mentions
```

That writes `data/mysql-init/04-mentions.sql`, which then gets replayed automatically on the
next fresh volume along with everything else. It's rows only — the table itself is created by
`03-schema-and-companies.sql`, and keeping the DDL in one place stops the two copies drifting
apart. The inserts skip any mention already present, so replaying an old snapshot over a newer
database tops it up rather than clobbering it.

Re-run it whenever you want the snapshot to catch up. In particular, if you re-classify
everything after changing models, refresh it — otherwise a stale snapshot keeps restoring the
old labels.

For an ordinary backup rather than a seed file:

```bash
cd data
./seed-db.sh --export
```

That drops a timestamped dump in `backups/`, which git ignores.

---

## Configuration

Everything is environment-overridable. Defaults live in each service's `application.yml`.

**Backend**

| Variable | Default | What it does |
|---|---|---|
| `OLLAMA_MODEL` | `qwen3:4b` | model tag; read the section above before going bigger |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | where Ollama is |
| `OLLAMA_TIMEOUT` | `60` | seconds; a timeout means that article becomes UNKNOWN |
| `MONITORING_CONCURRENCY` | `6` | companies handled at once; keep at or below the DB pool size |
| `MONITORING_RSSCONCURRENCY` | `2` | concurrent Google News requests; keep this low |
| `MONITORING_QUARTERDAYS` | `90` | must match the frontend's value |
| `MONITORING_DAILYCRON` | `0 0 7 * * ?` | Quartz cron syntax, not Spring's |
| `ALERT_WEBHOOK_URL` | a webhook.site test inbox | where new-coverage alerts get POSTed; set it in `docker-compose.yml`, see [Alerts](#alerts) |

**Frontend**

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` | the port the browser hits |
| `SPRING_DATASOURCE_USERNAME` | `pressmonitor_ro` | read-only user |
| `MONITORING_QUARTERDAYS` | `90` | must match the backend's value |
| `BACKEND_BASE_URL` | `http://localhost:8081` | for the two proxied endpoints |

The two `MONITORING_QUARTERDAYS` really do have to agree. Both services work out "this quarter"
independently against their own connection, so if they drift the dashboards disagree with each
other.

### Endpoints

| Endpoint | What it gives you | Who serves it |
|---|---|---|
| `GET /` | the dashboard | frontend |
| `GET /api/summary` | quarter totals and sentiment split | frontend, straight from MySQL |
| `GET /api/companies` | the company status table | frontend, straight from MySQL |
| `GET /api/companies/{id}/mentions` | one company's mentions | frontend, straight from MySQL |
| `GET /api/mentions/recent` | latest mentions across everything | frontend, straight from MySQL |
| `GET /api/timeline` | weekly sentiment counts | frontend, straight from MySQL |
| `POST /api/run`, `GET /api/run/status` | start and poll a run | backend, proxied by the frontend |
| `GET /api/health` | is Ollama reachable | backend, proxied by the frontend |

---

## A note on ports

Only the frontend's 8080 is bound to every interface. MySQL, Ollama and the backend are all
bound to `127.0.0.1`, so they're reachable from the machine running Compose and nowhere else.

This isn't hypothetical tidiness. Docker's `ports:` binds `0.0.0.0` by default and writes its
own firewall rules, so a host firewall often isn't protecting what you think. With the backend
exposed, `POST /api/run` is an unauthenticated button that starts a few thousand LLM calls, and
an exposed MySQL is a database whose password is sitting in a file in this repo. Internet-wide
scanners find these in hours.

To reach the backend from another machine, tunnel to it rather than republishing the port:

```bash
ssh -L 8081:localhost:8081 <host>
```

The frontend has no authentication of its own either. Before putting it anywhere public, it
wants a reverse proxy with TLS and access control in front, and those MySQL passwords want
moving into real secrets.

---

## News sourcing, and where it falls down

Google News RSS, because it needs no API key and no signup, so this project can just be cloned
and run. The trade-offs are real though.

You only get a headline and a short snippet, never the article body, so sentiment is judged on
very little. Links are Google redirects rather than direct publisher URLs. And company names are
ambiguous, as covered above.

The awkward one is that this is an unofficial, unauthenticated endpoint, and Google defends it.
Past a certain volume it stops returning a feed and starts returning an HTML "unusual traffic"
page instead — sometimes with a 503, sometimes with a perfectly innocent-looking 200. Parsed
naively that reads as "this company has no news", and every remaining company in the run comes
back empty.

So there's a delay between requests with some jitter, a hard cap of two concurrent RSS requests,
and a circuit breaker. The first time a block page appears, RSS fetching stops for a few minutes
rather than hammering something that's already refusing, doubling on repeats up to twenty
minutes. Companies skipped during a cooldown are set aside and retried once it lifts, rather
than being recorded as having no coverage.

If blocking becomes persistent, the real fix is a source with an API key. Swapping one in means
writing another `NewsCollector` — the rest of the pipeline doesn't care where articles come from.

---

## Choices I'd defend, and ones I'd revisit

Sentiment is judged from the company's point of view, not the author's tone. An article that's
neutral in register can still be bad news for the company it's about.

Duplicate detection hashes the article URL, falling back to the title. That's what makes re-runs
cheap and stops the daily alert repeating itself.

Three independent Maven builds rather than one reactor is closer to how genuinely separate
deployables consume a shared library, at the cost of having to `mvn install` shared-library
before the other two. The Dockerfiles handle that ordering for you.

Things I'd want before calling this production-ready: authentication on the dashboard and on
`POST /api/run`; article body fetching for better sentiment; a second news source with proper
API access; and real migrations via Flyway or Liquibase instead of numbered SQL files applied by
a shell script. The Quartz job store also isn't set up for clustering, so this expects to run as
a single backend instance.

`monitoring.quarter-days` being duplicated across both services is a genuine wart. It's the
price of letting the frontend compute its own aggregates instead of asking the backend, which
otherwise buys a lot.

---

## Tests

```bash
mvn -f backend/pom.xml test
mvn -f frontend/pom.xml test
```

`SentimentClassifierTest` covers pulling JSON out of messy model output — including reasoning
models that wrap their answer in a thinking block, and braces appearing inside string values,
both of which broke an earlier and more naive version. `DomainLogicTest` covers slug generation
and dedup key stability. `MentionStatusTest` covers the status buckets and their boundaries.

Building by hand, if you're not using Docker:

```bash
mvn -f shared-library/pom.xml install   # must come first
mvn -f backend/pom.xml package
mvn -f frontend/pom.xml package
```
