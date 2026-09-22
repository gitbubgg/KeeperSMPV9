# KeeperSMP

One server-side jar for a Paper + Geyser + Floodgate server. Java and Bedrock
players get the same commands and the same menus. Nothing is installed on any
player's client.

## What is in it

| Command | What it does |
| --- | --- |
| `/menu` | Central menu: shop, auction, homes, rtp, balance, settings, ranks |
| `/shop` | Paged buy menu using the prices in `config.yml` |
| `/sell`, `/sell all` | Sell the item in your main hand, or everything sellable |
| `/autosell` | Cash in sellable drops as you pick them up (Keeper+ and above) |
| `/ah`, `/auction` | Browse the auction house |
| `/ah sell <price>` | List the item in your **main hand**, then `/ah confirm` |
| `/ah mine` | Your own listings, click to pull one back |
| `/sethome <name>`, `/home [name]`, `/delhome`, `/homes` | Homes, limited by rank |
| `/rtp` | Random drop in the wild, per-rank cooldown |
| `/rtpq` | Join a queue; the next person to queue drops in with you |
| `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` | Teleport requests |
| `/back` | Return to where you were (Keeper+ and above) |
| `/settings` | Mob spawning, tpa, tpahere, auto sell |
| `/rank` | Rank menu with a personal Stripe checkout link |
| `/balance`, `/pay <player> <amount>` | Money |
| `/ec`, `/craft` | Ender chest and crafting anywhere (Keeper++ and above) |
| `/nick <name>` | Coloured nickname (Keeper+ and above) |
| `/fly` | Admin and Owner |
| `/gmc`, `/forcetp <player>` | Mod and above. `/forcetp` ignores request toggles |
| `/grantrank <player> <rank> [days]` | Admin and Owner |
| `/eco <give\|take\|set> <player> <amount>` | Admin and Owner |
| `/strata reload`, `/strata stripe` | Admin and Owner |

Combat tagging blocks `/rtp`, `/rtpq`, `/tpa`, `/tpahere`, `/home` and `/back`
for 20 seconds after any player-versus-player hit. Staff are exempt. The timer
is `general.combat-tag-seconds`.

Mob spawning off means: hostile mobs will not spawn within 64 blocks of you,
and any hostile that wanders into range is removed, but only while nobody in
range has spawning switched on. One player with it on keeps mobs alive for
everyone standing near them, which is the only way this can work without two
players in the same chunk contradicting each other.

## Building the jar

No Java on your machine needed if you use GitHub.

1. Put this folder in a new GitHub repo and push it.
2. The included workflow builds it on every push. Actions tab, newest run,
   download `KeeperSMP-jar`.

With Java 21 and Maven locally instead: `mvn clean package`, then take
`target/KeeperSMP.jar`.

## Installing on FalixNodes

1. Server must be **Paper 1.21.x** with Geyser and Floodgate already in
   `plugins`, which you have.
2. Upload `KeeperSMP.jar` to `plugins`.
3. Restart. Stop and start, not reload.
4. Edit `plugins/KeeperSMP/config.yml`, then `/strata reload`.

## Staff ranks

Set in `config.yml`, not bought:

```yaml
staff:
  owner: ['Ytismyfav', '.HappyVids3312']
  mod: ['.Fitz fun5811', '.Fitz_fun5811', '.SloppyStar3815']
```

Floodgate prefixes Bedrock names (default `.`) and turns spaces in a gamertag
into underscores, so `.Fitz fun5811` may arrive as `.Fitz_fun5811`. Both
spellings are listed and matching ignores dots, spaces and case, so either way
works. Check the exact in-game name with `/list` once they join and trim the
list if you like.

## Stripe rank delivery

The plugin polls Stripe once a minute. No public URL, no port forwarding, no
certificate, which is what makes it work on a hosted panel.

1. Stripe dashboard, Developers, API keys, **Create restricted key**.
2. Grant **read** on *Checkout Sessions* and *Subscriptions*. Nothing else.
3. Paste it into `config.yml` under `stripe.secret-key` and set
   `stripe.enabled: true`.
4. `/strata stripe` should report `connected`.

How a purchase flows:

1. Player runs `/rank` and clicks a tier.
2. They get a code such as `STX-7K4QP` and a checkout link with that code
   already attached as `client_reference_id`.
3. They pay on Stripe's hosted page. No card details ever touch the server,
   which keeps you out of PCI scope.
4. Within a minute the poller matches the code to their account, checks the
   amount against `stripe.amounts`, and grants the rank until the end of the
   billing period.
5. Each poll re-checks live subscriptions. Cancelled or unpaid drops them back
   to Keeper automatically.

If someone pays without the code, nothing breaks, the rank just does not land
on its own. Fix it with `/grantrank <player> KEEPER_PLUS2 30`.

Codes last 30 minutes by default and survive a restart.

## Two things worth deciding

**Mojang's commercial guidelines** limit selling gameplay advantage on public
servers. The `sell-bonus` and `shop-discount` perks are the two most likely to
draw a complaint. Switch either off server-wide without touching the code:

```yaml
perks:
  sell-bonus: false
  shop-discount: false
```

Homes, listing slots, nicknames, `/ec` and `/craft` are convenience perks and
are the safer side of that line.

**Keeper+++ was priced but never spec'd**, so it was filled in as a step above
Keeper++: 60 homes, 75 listings, 1% auction fee, 7 day listings, instant
teleports, 30 second `/rtp`. Change any of it in the `ranks` block.

## Data files

```
plugins/KeeperSMP/
  config.yml          prices, perks, staff, stripe
  players/<uuid>.yml  balance, rank, homes, settings
  auctions.yml        live listings and undelivered items
  stripe-state.yml    pending codes, handled sessions
```

Balances and listings are written every five minutes and on shutdown. Stop the
server properly rather than killing the container.

## Not yet built

- The code has not been compiled or run against a live server. Expect one or
  two small fixes on the first build; send me the error and I will patch it.
- Prices are a first pass. Watch what players farm for the first week, then
  adjust `sell` in the config.
