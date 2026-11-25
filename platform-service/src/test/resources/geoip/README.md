# ip2region Test Fixtures

The two XDB fixtures were generated with the official Java Maker from upstream
commit `907400bb3023ce48aaedf34453a81c0073b7cef0`. Each database contains only the
single range in its matching `_source.txt` file. They exercise the real XDB file
loading and IPv4/IPv6 routing paths without checking the full production data
files into Git.
