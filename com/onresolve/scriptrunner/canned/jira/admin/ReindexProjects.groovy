package com.onresolve.scriptrunner.canned.jira.admin

import com.atlassian.jira.bc.filter.SearchRequestService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.index.IssueIndexingParams
import com.atlassian.jira.issue.search.SearchRequest
import com.atlassian.jira.issue.search.SearchResults
import com.atlassian.jira.util.ImportUtils
import com.onresolve.scriptrunner.canned.CannedScript
import com.onresolve.scriptrunner.canned.jira.utils.CannedScriptUtils
import com.onresolve.scriptrunner.canned.jira.utils.FilterUtils
import com.onresolve.scriptrunner.canned.util.BuiltinScriptErrors
import com.onresolve.scriptrunner.canned.util.SimpleBuiltinScriptErrors
import org.apache.log4j.Logger
import com.atlassian.jira.project.Project
import com.atlassian.jira.jql.builder.JqlQueryBuilder
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.project.ProjectManager

/** Reindexes issues from specific projects.
 * 
 * Tested on JIRA 9.12.5. 
 * 
 * @author jeff@redradishtech.com
 */ 

/*
 * Copyright 2016 Jeff Turner
 * 
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 * 
 * 	    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 */
public class ReindexProjects implements CannedScript {

	public static String FIELD_PROJECT_ID = "FIELD_PROJECT_ID";

	def authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
	def SearchRequestService searchRequestService = ComponentAccessor.getComponent(SearchRequestService);
	def IssueIndexingService issueIndexService = ComponentAccessor.getComponent(IssueIndexingService);
	def IssueManager issueManager = ComponentAccessor.getIssueManager();
	def ProjectManager projectManager = ComponentAccessor.getProjectManager();

	Logger log = Logger.getLogger(ReindexProjects.class)

		public String getName() {
			"Reindex projects"
		}

	List getCategories() {
		["ADMIN"]
	}

	public String getDescription() {
		"""Reindex issues in particular projects, perhaps after an indexing problem or editing the database. Don't worry if you get a timeout error, the reindex will complete in the background (check the logs)."""
	}

	public List getParameters(Map params) {
		[
			[
			Name:FIELD_PROJECT_ID,
			Label:"Project(s)",
			Description:"""All issues in these projects will be reindexed.""",
			Type:"multilist",
			value: [],
			Values: CannedScriptUtils.getProjectOptions(false),
			],
		]
	}

	public BuiltinScriptErrors doValidate(Map params, boolean forPreview) {
		BuiltinScriptErrors errorCollection = new SimpleBuiltinScriptErrors();
		if (! ((params[FIELD_PROJECT_ID]) as Boolean)) {
			errorCollection.addErrorMessage("Please select a project")
				return errorCollection
		}
		// Trust that we're given valid project keys.
		return errorCollection
	}

	/** Get all issues in the specified projects, by looking at the database, not the search index. */
	List<Issue> getIssuesFromDatabase(Map params) {
		List<Issue> issues = new ArrayList<Issue>();
		if (params[FIELD_PROJECT_ID]) {
			// We might have either a single project key, or an array of keys.
			List<String> projects = (params[FIELD_PROJECT_ID] instanceof String) ? [params[FIELD_PROJECT_ID] as String ] : params[FIELD_PROJECT_ID] as List<String>;

			projects.each { projectKey ->
				Project proj = projectManager.getProjectByCurrentKey(projectKey);
				Collection<Long> issueIds = issueManager.getIssueIdsForProject(proj.id);
				issues.addAll(issueManager.getIssueObjects(issueIds));
				log.info("Got ${issueIds.size()} results from project ${projectKey}.");
			}
		}
		log.info("In total, got ${issues.size()} issues");
		return issues;
	}

	Map doScript(Map params) {
		// First we deindex all issues returned by a search, then we index all issues returned from a database query.
		deindexOldIssues(params);
		def output = indexNewIssues(params);
		["output":output]
	}

	/** Remove all issue index entries for our projects. */
	String deindexOldIssues(Map params) {
		// It would be nice if JIRA had a "remove all issues in project X" method, but it doesn't appear to. 
	 	// Instead we do a JIRA search to find all issues in the old index, then deindex each one.
		def jql = createSearchRequest(params);
		log.error(jql);
		log.error("Deindexing ${Issues.count(jql)} issues returned by query ${jql}");
		def Set<Issue> issues = Issues.search(jql).toSet()
		issueIndexService.deIndexIssueObjects(issues, true);
	}

	/** For each project, index its issues. */
	String indexNewIssues(Map params) {
		List<Issue> issues = getIssuesFromDatabase(params);

		// Tons of code on the internet has a { setIndexIssues(true); messwithindexes(); setIndexIssues(false); } pattern, but it is all using IndexManager, not the new IssueIndexService. I'm going to leave out 
		//boolean wasIndexing = ImportUtils.isIndexIssues();
		//ImportUtils.setIndexIssues(true);
		//IssueIndexingParams iip = IssueIndexingParams.builder().setComments(true).setForceReloadFromDatabase(true).build();

		log.error("Now reindexing ${issues.size()} issues...");
		long time = issueIndexService.reIndexIssueObjects(issues, IssueIndexingParams.INDEX_ALL);
		def msg = "Completed reindex ${issues.size()} issues in ${time} ms"
		log.error(msg);
		msg
	}

	/** Create a search request returning issues from one or more projects. */
	String createSearchRequest(Map params) {
		List projects = (params[FIELD_PROJECT_ID] instanceof String) ? [params[FIELD_PROJECT_ID]] : params[FIELD_PROJECT_ID];
		"project in (${projects.join(',')})"
	}

	String getDescription(Map params, boolean forPreview) {

		List<Issue> issues  = getIssuesFromDatabase(params);
		"""Will reindex ${issues.size()} issue(s)... """
	}

	public Boolean isFinalParamsPage(Map params) {
		true
	}

}
