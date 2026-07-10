# Electronic Muyu uses no reflection-based application model serialization.
# AndroidX, OkHttp and coroutines provide their own consumer rules, so broad
# application keep rules would only disable useful R8 shrinking/obfuscation.
