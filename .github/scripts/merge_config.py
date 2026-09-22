"""Adds any config.yml key the repo's default has that the live server's
copy doesn't, leaving everything already on the server (values, comments,
formatting) untouched. saveDefaultConfig() only ever writes config.yml once,
the very first time the plugin starts, so this is how new config sections
actually reach a server that already has a config.yml.
"""

from ruamel.yaml import YAML

LIVE_PATH = "server-config.yml"
DEFAULT_PATH = "src/main/resources/config.yml"

yaml = YAML()
yaml.preserve_quotes = True

with open(LIVE_PATH) as f:
    live = yaml.load(f) or {}
with open(DEFAULT_PATH) as f:
    default = yaml.load(f) or {}


def merge(default_node, live_node):
    if not hasattr(default_node, "items"):
        return
    for key, value in default_node.items():
        if key not in live_node:
            live_node[key] = value
        elif hasattr(value, "items") and hasattr(live_node.get(key), "items"):
            merge(value, live_node[key])


merge(default, live)

with open(LIVE_PATH, "w") as f:
    yaml.dump(live, f)
