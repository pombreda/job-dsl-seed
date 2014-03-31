// https://github.com/eclipse/egit-github/tree/master/org.eclipse.egit.github.core
@Grab(group='org.eclipse.mylyn.github', module='org.eclipse.egit.github.core', version='2.1.5')
import org.eclipse.egit.github.core.*
import org.eclipse.egit.github.core.client.*
import org.eclipse.egit.github.core.service.*
import static org.eclipse.egit.github.core.client.IGitHubConstants.*;

import com.google.gson.reflect.TypeToken;

// TODO Set name of build to the version being released, might require a module.properties like file
// TODO Index jobs, so that customizations can be easily added
// TODO Publish latest version (or each version of Gradle) somewhere, like the netflix-plugins.github.io page

GitHubClient client = new GitHubClient()

def githubProperties = new File(GITHUB_PROPERTIES?:System.getenv()['GITHUB_PROPERTIES'])
if (githubProperties.exists()) {
    def props = new Properties()
    githubProperties.withInputStream { 
        stream -> props.load(stream) 
    }
    def gitHubCredentials = props['github.oauth']

    //OAuth2 token authentication
    client.setOAuth2Token(gitHubCredentials)
} else {
    println "Not providing credentials"
}

def PULL_REQUEST_URL = 'https://netflixoss.ci.cloudbees.com/github-pull-request-hook/'
def WEB_HOOK_URL = 'https://netflixoss.ci.cloudbees.com/github-webhook/'

def orgName = 'nebula-plugins'
def folderName = 'nebula-plugins/'

RepositoryServiceExtra repoService = new RepositoryServiceExtra(client);
TeamService teamService = new TeamService(client);
List<Team> teams = teamService.getTeams(orgName)

def regex = [/gradle-(.*)-plugin/, /nebula-(\p{Lower}+)$/, /nebula-(.*)-plugin/]
regex = [/gradle-ospackage-plugin/]
repoService.getOrgRepositories(orgName).findAll { repo -> regex.any { repo.name =~ it } }.each { Repository repo ->
    def repoName = repo.name
    def description = "${repo.description} - http://github.com/$orgName/$repoName"

    println "Creating jobs for $repoName"

    List<RepositoryBranch> branches = repoService.getBranches(repo)
    def gradleBranches = branches.findAll { it.name.startsWith('gradle-') }
    gradleBranches.collect { RepositoryBranch branch ->
        def nameBase = "${folderName}${repo.name}-${branch.name - 'gradle-'}"
        snapshot(nameBase, description, orgName, repoName, branch.name )
        release(nameBase, description, orgName, repoName, branch.name )
    }

    if (gradleBranches.isEmpty()) {
        // Master
        def nameBase = "${folderName}${repo.name}"
        snapshot(nameBase, description, orgName, repoName, 'master')
        release(nameBase, description, orgName, repoName, 'master')
    }

    // Pull Requests are outside of a specific branch
    pullrequest("${folderName}${repo.name}", description, orgName, repoName, '*' ) // Not sure what the branch should be

    // Establish WebHooks
    List<RepositoryHook> hooks = repoService.getHooksExtra(repo)

    addHook(hooks, repoService, repo, PULL_REQUEST_URL, ['pull_request'] as String[])
    addHook(hooks, repoService, repo, WEB_HOOK_URL, ['push'] as String[])
    addTeam(teams, teamService, orgName, repo, "${repo.name}-contrib", 'push')
    //addTeam(teams, teamService, orgName, repo.name, "${repo.name}-admin", 'admin')
}

// Via https://github.com/barchart/barchart-pivotal-github/tree/master/src/main/java/com/barchart/github

public class RepositoryHookExtra extends RepositoryHook {

    private static final long serialVersionUID = 1L;

    private volatile String[] events = new String[0];

    public RepositoryHookExtra setEvents(final String[] events) {
        this.events = events;
        return this;
    }

    public String[] getEvents() {
        return events;
    }

}

public class RepositoryServiceExtra extends RepositoryService {

    public RepositoryServiceExtra(final GitHubClient client) {
        super(client);
    }

    @Override
    public RepositoryHookExtra createHook(
            final IRepositoryIdProvider repository, final RepositoryHook hook)
            throws IOException {
        final String id = getId(repository);
        final StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
        uri.append('/').append(id);
        uri.append(SEGMENT_HOOKS);
        return client.post(uri.toString(), hook, RepositoryHookExtra.class);
    }

    public List<RepositoryHookExtra> getHooksExtra(
            final IRepositoryIdProvider repository) throws IOException {
        final String id = getId(repository);
        final StringBuilder uri = new StringBuilder(SEGMENT_REPOS);
        uri.append('/').append(id);
        uri.append(SEGMENT_HOOKS);
        final PagedRequest<RepositoryHookExtra> request = createPagedRequest();
        request.setUri(uri);
        request.setType(new TypeToken<List<RepositoryHookExtra>>() {
        }.getType());
        return getAll(request);
    }
}

def addHook(List<RepositoryHook> hooks, RepositoryService repoService, Repository repo, String hookUrl, String[] events) {
    def hasWebHook = hooks.any { RepositoryHook hook -> println "${hook.config.get('url')} vs ${hookUrl}"; hook.config.get('url') == hookUrl }
    if (!hasWebHook) {
        def hook = new RepositoryHookExtra()
                .setActive(true)
                .setConfig([url: hookUrl, content_type: 'form'])
                .setCreatedAt(new Date())
                .setName('web')
                .setUpdatedAt(new Date())
                .setEvents(events)
        println GsonUtils.getGson().toJson(hook);
        repoService.createHook(repo, hook)
    }
}

// Create team for a single repository
def addTeam(List<Team> teams, TeamService teamService, String orgName, Repository repository, String contribTeamName, String permission) {
    def foundTeam = teams.find { Team team -> team.name == contribTeamName }
    if (!foundTeam) {
        def team = new Team()
                .setPermission(permission) // 'push' vs 'admin'
                .setName(contribTeamName)
        println GsonUtils.getGson().toJson(team);
        foundTeam = teamService.createTeam(orgName, team)
    }

    List<Repository> repos = teamService.getRepositories(foundTeam.id)
    def hasRepo = repos.any { Repository repo -> repo.name == repo.name }
    if (!hasRepo) {
        teamService.addRepository(foundTeam.id, repository)
    }
}

// Ensure this repo is in the org-wide "contrib"
def ensureInContrib(TeamService teamService, String contribTeamName) {
    def foundTeam = teams.find { Team team -> team.name == contribTeamName }
    if (!foundTeam) {
    }
}

def base(String repoDesc, boolean linkPrivate = true) {
    job {
        description ellipsize(repoDesc, 255)
        logRotator(60,-1,-1,20)
        wrappers {
            timeout(20)
        }
        jdk('Sun JDK 1.6 (latest)')
        if (linkPrivate) {
            steps {
                shell('''
                if [ ! -d $HOME/.gradle ]; then
                   mkdir $HOME/.gradle
                fi
    
                if [ ! -e $HOME/.gradle/gradle.properties ]; then
                   ln -s /private/netflixoss/gradle/gradle.properties $HOME/.gradle/gradle.properties
                fi
                '''.stripIndent())
            }
        }
        configure { project ->
            project / 'properties' / 'com.cloudbees.jenkins.plugins.PublicKey'(plugin:'cloudbees-public-key@1.1')
            if (linkPrivate) {
                project / buildWrappers / 'com.cloudbees.jenkins.forge.WebDavMounter'(plugin:"cloudbees-forge-plugin@1.6")
            }
        }
        publishers {
            archiveJunit('**/build/test-results/TEST*.xml')
        }
    }
}

def release(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc)
    job.with {
        name "${nameBase}-release"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                //it / userRemoteConfigs / 'hudson.plugins.git.UserRemoteConfig' / credentialsId(gitHubCredentials)
                it / extensions / 'hudson.plugins.git.extensions.impl.LocalBranch' / localBranch(branchName)
            }
        }
        steps {
            gradle('clean release --stacktrace --refresh-dependencies')
        }
    }
}

def snapshot(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc)
    job.with {
        name "${nameBase}-snapshot"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                it / skipTags << 'true'
            }
        }
        triggers {
            cron('@daily')
        }
        steps {
            gradle('clean build snapshot --stacktrace --refresh-dependencies') // TODO a bad nebula-pluging is cached right now, and a refresh workspace won't fix it.
        }
        configure { project ->
            project / triggers / 'com.cloudbees.jenkins.GitHubPushTrigger' / spec
        }
    }
}

def pullrequest(nameBase, repoDesc, orgName, repoName, branchName) {
    def job = base(repoDesc, false)
    job.with {
        name "${nameBase}-pull-requests"
        scm {
            github("${orgName}/${repoName}", branchName, 'ssh') {
                it / skipTags << 'true'
            }
        }
        steps {
            gradle('clean check --stacktrace --refresh-dependencies')
        }
        configure { project ->
            project / triggers / 'com.cloudbees.jenkins.plugins.github__pull.PullRequestBuildTrigger'(plugin:'github-pull-request-build@1.0-beta-2') / spec ()
            project / 'properties' / 'com.cloudbees.jenkins.plugins.git.vmerge.JobPropertyImpl'(plugin:'git-validated-merge@3.6') / postBuildPushFailureHandler(class:'com.cloudbees.jenkins.plugins.git.vmerge.pbph.PushFailureIsFailure')
        }
        publishers {
            // TODO Put pull request number in build number, $GIT_PR_NUMBER
        }
    }
}

String ellipsize(String input, int maxLength) {
  if (input == null || input.length() < maxLength) {
    return input;
  }
  return input.substring(0, maxLength) + '...';
}
