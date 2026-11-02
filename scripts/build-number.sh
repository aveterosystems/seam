#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Both apps' build number: minutes since 1 January 2026, UTC. Stable and nightly builds share it, so
# a later build always has a higher number, and Android and Sparkle update across the two channels.
echo $(( ($(date -u +%s) - 1767225600) / 60 ))
