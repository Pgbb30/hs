# Swirlds Platform Wiki

This document contains information about the Swirlds Platform. It is currently a work in progress.

The platform code is split into three categories:

- Base: common utilities and libraries; logging, configuration, metrics, etc.
- Core: gossip, consensus, data flow, and various algorithms
- Data: merkle data structures for holding the ledger state

## Base

This code is maintained by the "Platform Base" team.

- [Configuration](./base/configuration/configuration.md)
- Metrics
    - Prometheus
    - [Busy time metrics](./base/metrics/busy-time-metric.md)
- Logging
- Thread Management

## Core

This code is maintained by the "Platform Hashgraph" team.

- [System Startup Sequence](./core/system-startup-sequence.svg)
- [Platform Status](./core/platform-status.md)
- Components
    - Gossip
        - Sync gossip algorithm
        - Out of order gossip algorithm
    - Hashgraph
    - State management
        - [Rules for using SignedState objects](./core/signed-state-use.md)
        - State snapshots
        - Hashing
        - State Signing
        - ISS Detection
    - Reconnect
    - Transaction Handling
    - BLS
    - Application Communication
- Event Flow
    - Event Intake
    - Pre-consensus event stream
    - Post-consensus event stream
    - Threading Diagram

## Data

This code is maintained by the "Platform Data" team.

- Merkle APIs
    - Fast Copies
    - Mutability
    - Reference Counting
    - Hashing
    - Serialization
- Data Structures
    - VirtualMap
        - MerkleDB
    - MerkleMap
    - FCHashMap
    - FCQueue
- Reconnect

## Process

### Testing
### Pull Requests
### Documentation
#### Markdown Wiki
#### Mindmap