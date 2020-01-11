
ASM 是一个广泛使用的字节码操作库，前面之所以 ASM 需要一点操作，是因为你需要熟悉字节码，还有一些 ASM 中各种 API 之间的关系。其次，既然是操作字节码的，那我们肯定需要先拿到字节码，这就涉及到了 Gradle Transform API 了。但是使用 Gradle Transform API 是依赖于 Gradle Plugin 的，所以可以看出想学 ASM，还是需要很多前置知识的。

还不是因为可以结合 Transform API 做一些很神奇的事？我们可以在自定义 Plugin 中去注册我们的 Transform，以此来干预打包流程。

Transform 在 Android 中都会被封装成 Task，Task 就是 Gradle 的灵魂。Android 中常见的 Task 有 ProGuard 混淆、MultiDex 重分包等等，我们写的 Transform 是会首先执行的，执行完之后再执行 Android 自带的 Transfrom。因此我们可以利用 TinyPng 在打包的时候批量压缩 res 下的所有 png 图片，甚至可以修改字节码，把项目中的 new Thread 全部替换成 CustomThread 等，Transform API 还是很强大的，本节先讲如何去自定义一个 Gradle Plugin，下一篇会仔细讲 Transform API 的玩法。



## 自定义 Gradle Plugin 方式

自定义 Gradle Plugin 的编写入口有三种：

- 模块内直接编写；
- buildSrc 工程中编写；
- library 工程中编写再发布到仓库进行依赖。

### 模块内 build.gradle 中直接编写

可以在 build.gradle 中直接编写并直接应用；

```groovy

apply plugin: 'com.android.application'

class MyPlugin implements Plugin<Project>{
    @Override
    void apply(Project target) {
        // 定义一个名为 hello 的 Task。
        target.task("Hello"){
            println('Hello World')
        }
    }
}

apply plugin: MyPlugin

……
```
接着在 Terminal 输入 gradlew -q Hello，其中 -q 是为了只输出关键信息，不输出日志信息。便可只执行指定的 Task **Hello**，最后的输出结果如下：

```
Hello World
```

缺点：只能在当前模块使用。

### buildSrc 工程

buildSrc 的原理是：运行 Gradle 时，它会检查项目根目录是否存在一个名为 buildSrc 的目录。然后 Gradle会自动编译目录下的代码，并将其放入构建脚本的类路径中。因此还可以利用它去[管理项目的依赖](https://juejin.im/post/5b0fb0e56fb9a00a012b7fda)，让依赖支持自动补全和单击跳转。

buildSrc 和 library 配置和使用 gradle 插件的方式几乎一致，区别在于 library 需要发布到仓库中，因此具体的配置统一在下一小节说明。

缺点：只能在当前工程中使用。但是可以用于 plugin 开发阶段的调试。

### library 工程

首先新建一个 Java Library 的工程，名叫 "my_plugin"，然后在 my_plugin 工程的 build.gradle 添加一些依赖：

```
apply plugin: 'java-library'
apply plugin: 'groovy'
apply plugin: 'maven'

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])

    //Gradle Plugin 依赖
    implementation gradleApi()
    //本地发布 Plugin
    implementation localGroovy()
}

sourceCompatibility = "7"
targetCompatibility = "7"
```

我们引入一个依赖，其实是有三部分组成的：

compile 'com.android.tools.build:gradle:3.3.2'
//groupId: com.android.tools.build
//artifactId: gradle
//version: 3.3.2




https://docs.gradle.org/current/userguide/plugins.html