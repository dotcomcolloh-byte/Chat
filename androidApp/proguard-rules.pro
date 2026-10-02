# libsignal depends on the SLF4J API; no Android binding is bundled, so logging falls back to SLF4J's no-op provider.
-dontwarn org.slf4j.impl.StaticLoggerBinder
