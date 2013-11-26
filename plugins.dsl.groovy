
// https://github.com/eclipse/egit-github/tree/master/org.eclipse.egit.github.core
@Grab(group='org.eclipse.mylyn.github', module='org.eclipse.egit.github.core', version='2.1.5')
import org.eclipse.egit.github.core.*
import org.eclipse.egit.github.core.client.*
import org.eclipse.egit.github.core.service.*

def gitHubCredentials = jm.getCredentialsId('NEBULA_PLUGIN_OAUTH')

//OAuth2 token authentication
GitHubClient client = new GitHubClient()
client.setOAuth2Token(gitHubCredentials)

def orgName = 'nebula-plugins'

RepositoryService repoService = new RepositoryService(client);
def pluginNameRegex = /gradle-(.*)-plugin/
repoService.getOrgRepositories(orgName).findAll { it.name =~ pluginNameRegex }.each { Repository repo ->
    def repoName = repo.name
    def pluginName = (repoName =~ pluginNameRegex)[0][1]
    println "Plugin Name: ${pluginName}"

    List<RepositoryBranch> branches = repoService.getBranches(repo)
    branches.collect { RepositoryBranch branch ->
        job {
            name "nebula-${pluginName}-snapshot"
            scm {
                github("${orgName}/${repoName}", branch.name)
            }
            steps {
                gradle('clean build')
            }
        }
        job {
            name "nebula-${pluginName}-release"
            scm {
                github("${orgName}/${repoName}", branch.name) {
                    it / userRemoteConfigs / 'hudson.plugins.git.UserRemoteConfig' / credentialsId(gitHubCredentials)
                }
            }
            steps {
                gradle('clean release')
            }
        }
    }
}
