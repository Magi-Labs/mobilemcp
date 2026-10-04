# Security

MobileMCP's app is an accessibility service: once enabled it can read the screen and act on the phone. Treat the hub token like a password.

- Hubs that bind anything but loopback require `MOBILEMCP_TOKEN` or `MOBILEMCP_ACCOUNTS`; devices and agents of one account never see another's.
- The hub never stores screen content; actions are logged by name and duration only.
- Release builds ignore configuration intent extras so other apps cannot repoint the hub.
- Screen text is treated as data by the server instructions, never as instructions to the agent.

Report vulnerabilities privately to the maintainer (GitHub profile contact) rather than in a public issue. Expect an acknowledgement within a few days.
