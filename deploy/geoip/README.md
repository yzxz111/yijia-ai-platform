# Offline IP Location Data

The platform uses `ip2region` 3.3.7 to resolve successful-login IP addresses to a
coarse country/province/city value. No IP address is sent to an external service.

Provision the official IPv4 and IPv6 XDB files before starting the stack:

```powershell
./deploy/geoip/download-ip2region.ps1
```

The script downloads data pinned to upstream commit
`907400bb3023ce48aaedf34453a81c0073b7cef0`, verifies exact SHA-256 hashes, and
places these generated files in this directory:

- `ip2region_v4.xdb`
- `ip2region_v6.xdb`

The files are ignored by Git and mounted read-only at `/run/geoip`. Missing,
unreadable, invalid, or incompatible data resolves to `UNKNOWN` and never blocks
login. Run the script again only as a reviewed database update; a changed upstream
file fails checksum verification until the pinned commit and hashes are updated
together.

IP geolocation is approximate. Mobile networks, enterprise egress, carrier NAT,
VPNs, and proxies can report a city different from the user's physical location.
The risk engine therefore combines region changes with device and network history.
