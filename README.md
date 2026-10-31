# GraphFS

GraphFS is a distributed graph file system.

## Repository layout

| directory | what it is |
|---|---|
| [`FileBalancing`](FileBalancing) | online placement of file replicas on servers: files traversed together end up on the same server, each file keeps `k` replicas while servers fail and come back, and no server holds much more than its share |

See the [FileBalancing README](FileBalancing/README.md) for the algorithm and its results.

## License

[MIT](LICENSE)
