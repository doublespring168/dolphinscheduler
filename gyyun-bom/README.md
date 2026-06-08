# Introduction

The `gyyun-bom` module is used to manage the version of third part dependencies. If you want to import
`gyyun-xx` to your project, you need to import `gyyun-bom` together by below way,
this can help you to manage the version.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.gyyun.ds</groupId>
            <artifactId>gyyun-bom</artifactId>
            <version>${gyyun.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

If you want to override the version defined in `gyyun-bom` you can directly add the version at your
module's `dependencyManagement`.
