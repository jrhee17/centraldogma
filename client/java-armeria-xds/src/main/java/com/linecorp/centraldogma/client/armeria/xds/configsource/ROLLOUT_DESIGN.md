# Rollout Config Source Design

## Motivation

When an xDS configuration update is pushed, all servers currently apply it immediately.
This is risky — a bad config can take down the entire fleet at once.

We want a mechanism where only certain servers apply a new config initially,
and the rest hold off until an operator explicitly widens the rollout.

## Design

### Generic Rollout Layer

Rather than baking rollout logic into the Central Dogma config source specifically,
we introduce a **RolloutConfigSource** — a generic wrapper that can wrap any config source
and add incremental rollout capability.

### Proto Definition

```protobuf
message RolloutConfigSource {
  // Cluster name to connect to Central Dogma for fetching rollout state.
  string cluster_name = 1;

  // Path to the rollout policy file in Central Dogma (e.g., "project/repo/rollout.json").
  string rollout_path = 2;

  // The actual config source being wrapped.
  ConfigSource inner_config_source = 3;
}
```

### Rollout Policy File

A JSON file in Central Dogma that the rollout layer watches:

```json
{
  "host_pattern": "server-00[1-3]"
}
```

`host_pattern` is a regex matched against `Node.id` (which should be set to the
server's hostname in the bootstrap config).

Operator controls:
- Target specific canaries: `"server-001"`
- Target a group: `"server-0[0-1].*"`
- Full rollout: `".*"`
- Hold everyone: `""` (matches nothing)

### Server Identity

The xDS bootstrap `Node` already carries server identity (id, cluster, locality, metadata).
`FactoryContext` needs to expose the `Node` so the rollout layer can access `Node.id`.
This is a trivial addition on the Armeria side.

## Behavior

### Output Gating (not Input Gating)

The rollout layer **always streams** from the inner config source — watchers remain
active on all servers regardless of rollout state. Updates are gated at the **output**,
not the input.

Rationale:
- **Observability**: All servers receive and track discovery responses in metrics,
  allowing operators to validate the new config across the fleet before widening rollout.
- **No cold start**: When the rollout pattern expands to include a server, it can apply
  the buffered config immediately without waiting for watchers to establish and receive
  the first response.
- **Acceptable cost**: The rollout state is temporary (goal is eventually `".*"`), so
  the extra watcher overhead is short-lived.

### Buffering

The rollout layer buffers the latest `DiscoveryResponse` per type URL:

```
Map<String typeUrl, DiscoveryResponse latest>
```

- **Gate closed** (pattern does not match this server): Buffer incoming responses per type,
  update metrics, but do not emit downstream.
- **Gate opens** (pattern changes to match this server): Flush all buffered responses
  downstream, then switch to pass-through mode.
- **Gate closes again** (pattern changes to exclude this server): Stop emitting,
  resume buffering. The previously applied config remains active on the server.

### Flow

```
1. RolloutConfigSource factory wraps the inner config source
2. Inner config source streams DiscoveryResponse updates (CDS, EDS, LDS, RDS, etc.)
3. Rollout layer watches rollout_path via the CD cluster for host_pattern
4. On each DiscoveryResponse from inner source:
   a. Buffer latest per type URL
   b. Update discovery metrics
   c. If Node.id matches host_pattern → emit downstream (apply)
   d. If no match → hold
5. On rollout policy change:
   a. If newly matched → flush all buffered responses downstream
   b. If newly excluded → stop emitting (keep buffering)
```

## Bootstrap Example

```yaml
dynamic_resources:
  cds_config:
    custom_config_source:
      typed_config:
        "@type": "type.googleapis.com/.../RolloutConfigSource"
        cluster_name: "centraldogma-cluster"
        rollout_path: "myproject/xds/rollout.json"
        inner_config_source:
          custom_config_source:
            typed_config:
              "@type": "type.googleapis.com/.../CentralDogmaConfigSource"
              cluster_name: "centraldogma-cluster"

node:
  id: "server-001"
```

## Future Considerations

- Per-resource-type rollout (e.g., roll out CDS separately from EDS) — start with
  all-or-nothing for simplicity.
- Percentage-based rollout using consistent hashing of Node.id — deferred because
  regex on hostname gives operators explicit, predictable control.
- Automatic rollback on error metrics.
