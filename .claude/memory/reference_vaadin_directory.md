---
name: reference-vaadin-directory
description: "Vaadin community add-ons are published to the Vaadin Directory Maven repo, not Maven Central — check there before declaring an add-on unpublished"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 4878e101-3f89-431f-ba3c-67e683989ad5
---

Vaadin community add-ons (groupId `org.vaadin.addons` and `org.vaadin.*`) are typically published to the **Vaadin Directory** repository, **not** Maven Central. The repo URL is:

```
https://maven.vaadin.com/vaadin-addons
```

Maven Central searches (search.maven.org) will return zero results for these artifacts even when releases exist.

To use an add-on, declare both the repo and the dependency:

```xml
<repositories>
  <repository>
    <id>Vaadin Directory</id>
    <url>https://maven.vaadin.com/vaadin-addons</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.vaadin.addons</groupId>
  <artifactId>dramafinder</artifactId>
  <version>1.1.0</version>
</dependency>
```

**Why this matters:** when checking whether a Vaadin add-on is "released", `curl maven.search.org/...` or `gh api .../releases` may both return empty even though the artifact is downloadable. The authoritative source is the Vaadin Directory (vaadin.com/directory) plus the maven.vaadin.com repo.

**How to apply:** before telling the user an add-on is unpublished or recommending JitPack/git-install as a fallback, ask them for the artifact coordinates or check `https://vaadin.com/directory` directly. Saved after recommending JitPack for [[reference-dramafinder]] when 1.1.0 was in fact available from the Directory.
