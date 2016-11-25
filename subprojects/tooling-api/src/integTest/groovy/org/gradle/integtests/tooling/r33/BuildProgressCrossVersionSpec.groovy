/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.tooling.r33

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.tooling.BuildException
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType

@TargetGradleVersion(">=3.3")
class BuildProgressCrossVersionSpec extends ToolingApiSpecification {
    def "generates project configuration events for single project build"() {
        given:
        settingsFile << "rootProject.name = 'single'"

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def configureBuild = events.operation("Configure build")

        def configureRootProject = events.operation("Configure root project 'single'")
        configureRootProject.parent == configureBuild

        configureBuild.children == [configureRootProject]
    }

    def "generates project configuration events for multi-project build"() {
        given:
        settingsFile << """
            rootProject.name = 'multi'
            include 'a', 'b'
        """

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def configureBuild = events.operation("Configure build")

        def configureRoot = events.operation("Configure root project 'multi'")
        configureRoot.parent == configureBuild

        def configureA = events.operation("Configure project ':a'")
        configureA.parent == configureBuild

        def configureB = events.operation("Configure project ':b'")
        configureB.parent == configureBuild

        configureBuild.children == [configureRoot, configureA, configureB]
    }

    def "generates project configuration events when configuration fails"() {
        given:
        settingsFile << """
            rootProject.name = 'multi'
            include 'a', 'b'
        """
        file("a/build.gradle") << """
            throw new RuntimeException("broken")
"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def e = thrown(BuildException)
        e.cause.message =~ /A problem occurred evaluating project ':a'/

        def configureBuild = events.operation("Configure build")
        configureBuild.failed

        def configureRoot = events.operation("Configure root project 'multi'")
        configureRoot.parent == configureBuild

        def configureA = events.operation("Configure project ':a'")
        configureA.parent == configureBuild
        configureA.failed
        configureA.failures[0].message == "A problem occurred configuring project ':a'."

        configureBuild.children == [configureRoot, configureA]
    }

    def "generates events for nested project configuration"() {
        given:
        settingsFile << """
            rootProject.name = 'multi'
            include 'a', 'b'
        """
        buildFile << """
            allprojects { apply plugin: 'java' }
            
            evaluationDependsOn(':a')
"""
        file("a/build.gradle") << """
            evaluationDependsOn(':b')
"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def configureBuild = events.operation("Configure build")

        def configureRoot = events.operation("Configure root project 'multi'")
        configureRoot.parent == configureBuild
        configureBuild.children == [configureRoot]

        def configureA = events.operation("Configure project ':a'")
        configureA.parent == configureRoot
        configureRoot.children == [configureA]

        def configureB = events.operation("Configure project ':b'")
        configureB.parent == configureA
        configureA.children == [configureB]
    }

    def "generates events for dependency resolution"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/Thing.java") << """class ThingTest { }"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .forTasks("build")
                        .run()
        }

        then:
        def compileJava = events.operation("Task :compileJava")
        def compileTestJava = events.operation("Task :compileTestJava")

        def compileClasspath = events.operation("Resolve configuration ':compileClasspath'")
        compileClasspath.parent == compileJava

        def testCompileClasspath = events.operation("Resolve configuration ':testCompileClasspath'")
        testCompileClasspath.parent == compileTestJava
    }

    def "generates events for failed dependency resolution"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }
            dependencies { compile 'thing:thing:1.0' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .forTasks("build")
                        .run()
        }

        then:
        def e = thrown(BuildException)
        e.cause.message =~ /Could not resolve all dependencies for configuration ':compileClasspath'./

        def compileClasspath = events.operation("Resolve configuration ':compileClasspath'")
        compileClasspath.failed
        compileClasspath.failures[0].message == "Could not resolve all dependencies for configuration ':compileClasspath'."
    }

    def "does not include dependency resolution that is a child of a task when task event are not included"() {
        given:
        buildFile << """
            allprojects { apply plugin: 'java' }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/Thing.java") << """class ThingTest { }"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events, OperationType.GENERIC)
                        .forTasks("build")
                        .run()
        }

        then:
        !events.operations.find { it.name == "Resolve configuration ':compileClasspath'" }
        events.operation("Run tasks").children.empty
    }

    def "generates events for interleaved project configuration and dependency resolution"() {
        given:
        settingsFile << """
            rootProject.name = 'multi'
            include 'a', 'b'
        """
        buildFile << """
            allprojects { apply plugin: 'java' }
            dependencies {
                compile project(':a')
            }
            configurations.compile.each { println it }
"""
        file("a/build.gradle") << """
            dependencies {
                compile project(':b')
            }
            configurations.compile.each { println it }
"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def configureBuild = events.operation("Configure build")

        def configureRoot = events.operation("Configure root project 'multi'")
        configureRoot.parent == configureBuild
        configureBuild.children == [configureRoot]

        def resolveCompile = events.operation("Resolve configuration ':compile'")
        resolveCompile.parent == configureRoot
        configureRoot.children == [resolveCompile]

        def configureA = events.operation("Configure project ':a'")
        configureA.parent == resolveCompile
        resolveCompile.children == [configureA]

        def resolveCompileA = events.operation("Resolve configuration ':a:compile'")
        resolveCompileA.parent == configureA
        configureA.children == [resolveCompileA]

        def configureB = events.operation("Configure project ':b'")
        configureB.parent == resolveCompileA
        resolveCompileA.children == [configureB]
    }

    def "generates buildSrc events"() {
        given:
        file("buildSrc/settings.gradle") << "include 'a', 'b'"
        file("buildSrc/build.gradle") << """
            allprojects {   
                apply plugin: 'java'
            }
            dependencies {
                compile project(':a')
                compile project(':b')
            }
"""
        file("buildSrc/a/src/main/java/A.java") << "public class A {}"
        file("buildSrc/b/src/main/java/B.java") << "public class B {}"

        settingsFile << "rootProject.name = 'single'"
        buildFile << """
            new A()
            new B()
"""

        when:
        def events = new ProgressEvents()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                        .addProgressListener(events)
                        .run()
        }

        then:
        def buildSrc = events.operation("Build buildSrc")
        buildSrc.children.find { it.descriptor.displayName == 'Configure build' }
        buildSrc.children.find { it.descriptor.displayName == 'Run tasks' }
    }

}
