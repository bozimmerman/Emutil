import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.*
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.v2019_2.*

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2024.12"

project {

    buildType(Build)

    params {
        param("env.MY_SECRET_ID", "C6n0yoOrg7xpgLiL0miLi6O5N4OGpM0k")
        param("env.MY_SECRET_TOKEN", "EXZtuna-aZvfr3s7Xna3XP7yHW6nJuvVQtGwPU8K63uwbPUn8Sd_xaUu7419x7r-")
    }
}

object Build : BuildType({
    name = "Build"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        script {
            name = "Check Environment Variables"
            scriptContent = """
                echo "MY_SECRET_ID: %env.MY_SECRET_ID%"
                echo "MY_SECRET_TOKEN: %env.MY_SECRET_TOKEN%"
            """.trimIndent()
        }
        ant {
            id = "Ant"
            mode = antFile {
            }
            targets = "init"
        }
        step {
			name = "Upload Solsta build"
			type = "solstaRunner"
			param("argument.cons_client_id", "%env.MY_SECRET_ID%")
			param("argument.cons_secret", "%env.MY_SECRET_TOKEN%")
			param("argument.cons_product", "Emutil")
			param("argument.cons_env", "Java")
			param("argument.cons_repo", "Bin")
			param("argument.auto_create", "false")
			param("teamcity.build.version", "1.1")
			param("teamcity.build.workingDir", ".")

			val includes = listOf("*")
			val exes = listOf("*.exe")
			val excludes = emptyList<String>()
			val hidden = emptyList<String>()
			if (includes.isNotEmpty()) {
				param("argument.included_files_json", "")
			}
			if (excludes.isNotEmpty()) {
				param("argument.excluded_files_json", "")
			}
			if (exes.isNotEmpty()) {
				param("argument.executable_files_json", "")
			}
			if (hidden.isNotEmpty()) {
				param("argument.hidden_files_json", "")
			}
		}    
    }

    triggers {
        vcs {
        }
    }

    features {
        perfmon {
        }
    }
})
