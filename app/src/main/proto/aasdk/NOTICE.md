# Vendored from aasdk

The `.proto` files in this directory are vendored **verbatim and unmodified** from:

- Project: aasdk (https://github.com/f1xpl/aasdk)
- Commit: `046b3b381595509d0939fa84b14a90978f46ff63`
- License: GNU GPLv3
- Copyright (c) 2018 f1x.studio (Michal Szwaj)

They are the real, public Android Auto protocol message definitions, used here only as a protocol
specification (compiled to Java message classes by the Gradle protobuf plugin). No other part of
aasdk - none of its C++ implementation - is used, linked, or reimplemented from its source anywhere
in this project.

If ClusterProbe is ever published or distributed beyond personal/private use, GPLv3's terms would
apply to anything incorporating these files - worth a deliberate look before that happens. See
`docs/H7_DISPLAY_ARCHITECTURE.md`'s "Android Auto receiver" section for the full rationale.
