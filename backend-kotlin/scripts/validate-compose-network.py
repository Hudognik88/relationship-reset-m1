#!/usr/bin/env python3
"""Check the resolved Compose proxy allocation and API trust boundary."""
import ipaddress
import json
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(config):
    proxy = config["networks"]["proxy"]
    require(proxy.get("internal") is True, "proxy network must remain internal")
    pools = proxy["ipam"]["config"]
    require(len(pools) == 1, "proxy must have one explicitly bounded IPv4 pool")
    subnet = ipaddress.IPv4Network(pools[0]["subnet"])
    dynamic = ipaddress.IPv4Network(pools[0]["ip_range"])
    require(dynamic.subnet_of(subnet), "dynamic pool must belong to the proxy subnet")

    caddy = config["services"]["caddy"]
    api = config["services"]["api"]
    caddy_ip = ipaddress.IPv4Address(caddy["networks"]["proxy"]["ipv4_address"])
    gateway = ipaddress.IPv4Address(pools[0].get("gateway", str(subnet.network_address + 1)))
    require(caddy_ip in subnet and caddy_ip not in
            (subnet.network_address, subnet.broadcast_address, gateway),
            "Caddy must have a usable static proxy address")
    require(caddy_ip not in dynamic,
            "dynamic API/migration endpoints could take Caddy's trusted static address")
    require("proxy" in api["networks"], "API must join the proxy network")
    require(not (api["networks"]["proxy"] or {}).get("ipv4_address"),
            "API must allocate dynamically so concurrent migration containers cannot collide")
    require(api["environment"]["RR_TRUSTED_PROXY_IPS"] == str(caddy_ip),
            "API must trust exactly Caddy's static proxy address")


if __name__ == "__main__":
    try:
        validate(json.load(sys.stdin))
    except (KeyError, TypeError, ValueError) as error:
        print("FAIL Compose proxy allocation: " + str(error), file=sys.stderr)
        sys.exit(1)
    print("PASS Compose proxy: static Caddy excluded from dynamic pool; API trust matches.")
