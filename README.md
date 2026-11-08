# Skateboard Factory

A multi-agent simulation of a small skateboard factory, built with [JaCaMo](https://jacamo-lang.github.io/)
(Jason agents, CArtAgO artifacts and a Moise organisation with NPL norms).

Each skateboard is its own agent. It travels through four stations (Trunks, Wheels, Torque and Optional),
chooses where to go next based on deadline, energy and quality, and is checked at the end against a set of norms.
Norm violations are detected and sanctioned. A web page shows the factory floor live.

## Run it

You need Java 21.

```bash
cd skt
./gradlew run
```

Then open <http://localhost:8088> to watch the factory and place orders.

## Main agents

<img width="766" height="345" alt="skateboard_factory(1)" src="https://github.com/user-attachments/assets/4813b26d-1c14-4cce-b59f-c5caf15f5413" />

| Agent | What it does |
|---|---|
| `twin_runner` | Builds the factory (stations, storages, policies) and starts the other agents. |
| `org_manager` | Creates the organisation and gives roles to the other agents. |
| `order_intake` | Turns web orders into skateboard agents. |
| `skate_creator` | Creates skateboards on a random timer. |
| `skateboard` | One per order. Moves through the stations and builds the board. |
| `quality_control` | Checks each finished order against the norms and applies sanctions. |
