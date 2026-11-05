dependencies {
    // Vault is optional at runtime; this is only needed to implement the Economy service.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit")
        exclude(group = "org.spigotmc")
    }
}
