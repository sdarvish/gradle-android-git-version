package com.gladed.gradle.androidgitversion

import org.gradle.api.Project
import org.gradle.api.Plugin
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk

class AndroidGitVersion implements Plugin<Project> {
    void apply(Project project) {
        project.extensions.create("androidGitVersion", AndroidGitVersionExtension, project)
        project.task('androidGitVersion') << {
            println "androidGitVersion.name\t${project.extensions.androidGitVersion.name()}"
            println "androidGitVersion.code\t${project.extensions.androidGitVersion.code()}"
        }
    }
}

class AndroidGitVersionExtension {
    /**
     * Prefix used to specify special text before the tag. Useful in projects which manage
     * multiple external version names.
     */
    String tagPrefix = ""

    /**
     * When true, only the current project directory is used to calculate commit counts
     */
    Boolean restrictDirectory = false

    /**
     * Specifies branches that are used for releases. When tagged and built from
     * one of these branches, only a simplified version name is created.
     */
    List<String> releaseBranches = [ "master" ]

    private Project project

    AndroidGitVersionExtension(Project project) {
        this.project = project
    }

    /**
     * Return a version name of the form MAIN[.COUNT-COMMIT][-BRANCH] where:
     *  - MAIN is the most recent tag that starts with a number (such as 1.3.14)
     *  - COUNT is the number of commits since that tag (skipped if COUNT is 0)
     *  - COMMIT is the commit prefix (such as 8f51448) (skipped if COUNT is 0)
     *  - BRANCH is the current branch name (skipped if a known release branch)
     */
    final def name() {
        Repository repo
        try {
            repo = new FileRepositoryBuilder().
                    readEnvironment().
                    findGitDir(project.rootDir).
                    build()
        } catch (IllegalArgumentException e) {
            // No repo found
            return "unknown"
        }

        def git = Git.wrap(repo)
        def head = repo.getRef(Constants.HEAD).getTarget()
        // No commits?
        if (!head.getObjectId()) return "unknown"

        def currentBranch = repo.getBranch()
        Iterable<RevCommit> commits = git.log().add(head.getObjectId()).all().call()
        Iterable<RevTag> tags = git.tagList().call().collect { ref ->
            RevWalk walk = new RevWalk(repo)
            walk.parseTag(ref.getObjectId())
        }

        def lastTags = commits.findResult { commit ->
            def tagsHere = tags.findAll { tag ->
                tag.getObject().getId().equals(commit) &&
                    tag.getTagName().matches('^' + tagPrefix + '[0-9].*$')
            }
            if (tagsHere) return tagsHere.collect { it.getTagName() - tagPrefix }
        }

        // No decent tags?
        if (!lastTags) return "unknown"
        return lastTags.first();

//        // Collect current build info
//        def commit = "git rev-parse --short HEAD".execute().text.trim()
//        def branch = "git rev-parse --abbrev-ref HEAD".execute().text.trim()
//        def lastTaggedCommit = "git describe --match ${tagPrefix}[0-9]* --tags --abbrev=0".
//                execute().text.trim()
//
//        println("****CURRENT COMMIT IS " + commit)
//        // Construct version if possible
//        if (!lastTaggedCommit) return "unknown"
//        def commitsSinceLastTag = ("git rev-list $lastTaggedCommit..HEAD" + (restrictDirectory ? " -- ." : "")).
//                execute().text.trim().readLines().size()
//        return gitLastTag(lastTaggedCommit, tagPrefix) +
//                (commitsSinceLastTag ? ".$commitsSinceLastTag-$commit" : "") +
//                (releaseBranches.contains(branch) ? "" : "-" + branch)
    }

    /**
     * Return a version code corresponding to current root version (as per tag).
     * For example a tag of 1.22.333 will return 100220333
     */
    final def code() {
        def lastTaggedCommit = "git describe --match ${tagPrefix}[0-9]* --tags --abbrev=0".
                execute().text.trim()
        return !lastTaggedCommit ? 0 : gitLastTag(lastTaggedCommit, tagPrefix).
                split(/[^0-9]+/).findAll().
                inject(0) { result, i -> result * 10000 + i.toInteger() };
    }


    // Given a tagged commit, find the numerically highest matching tag, without the prefix
    static String gitLastTag(String commit, String versionPrefix) {
        def tags = "git tag --points-at $commit".execute().text.trim().readLines().
                grep(~/^${versionPrefix}[0-9]+.*/)

        return tags.isEmpty() ? null :
                tags.sort(false) { a, b ->
                    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
                        [u,v].transpose().findResult{ x,y-> x<=>y ?: null } ?: u.size() <=> v.size()
                    }
                }.last() - versionPrefix
    }

}
