# Keep Java formatting compatible

Use google-java-format 1.36.1 with the current Spotless integration. The 1.37
formatter fails through that integration; version 2 also changes formatting and
enables source cleanups. Qualify the formatter and its integration together in a
separate pass, keeping formatting changes isolated from functional changes.
