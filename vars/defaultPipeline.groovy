#!groovy
/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements. See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership. The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied. See the License for the
  specific language governing permissions and limitations
  under the License.
*/
def call(body) {
  // Must not "def" these variables, otherwise they won't be available to the matrix steps!
  config = createConfiguration(body)
  labelValue = (params.agentLabel ?:config.agentLabel)?.trim()
  
  pipeline {
    parameters {
      string(
        name: 'extraMavenArguments',
        defaultValue: config.extraMavenArguments,
        description: "Extra arguments to be passed to maven (for testing; overrides only current build)")
      string(
        name: 'agentLabel',
        defaultValue: config.agentLabel,
        description: "Eligible agents (in case a build keeps running on a broken agent; overrides only current build)")
      booleanParam(
        name: 'wipeWorkspaceAfterBuild',
        defaultValue: config.wipeWorkspaceAfterBuild,
        description: "Wipe workspace after build (for testing; next build only)")
    }

    agent none
  
    tools {
      maven config.maven
      jdk config.jdk
    }
  
    options {
      buildDiscarder(logRotator(
        numToKeepStr: '25',
        artifactNumToKeepStr: '5'
      ))
    }
      
    stages {
      stage('Build') {
        matrix {
          axes {
            axis {
                name 'PLATFORM'
                values 'ubuntu', 'Windows'
            }
          }
  
          agent {
            label labelValue ? "(${labelValue}) && ${PLATFORM}" : "${PLATFORM}"
          }
           
          stages {
            // Display information about the build environemnt. This can be useful for debugging
            // build issues.
            stage("Info") {
              steps {
                script {
                  stage("${PLATFORM}") {
                    print "PLATFORM: ${PLATFORM}"
                  }
                }

                echo '=== Environment variables ==='
                script {
                  if (isUnix()) {
                    sh 'printenv'
                  }
                  else {
                    bat 'set'
                  }
                }
              }
            }
                
            // Perform a merge request build. This is a conditional stage executed with the GitLab
            // sources plugin triggers a build for a merge request. To avoid conflicts with other
            // builds, this stage should not deploy artifacts to the Maven repository server and
            // also not install them locally.
            stage("PR build") {
              when { branch 'PR-*' }
            
              steps {
                script {
                  currentBuild.description = 'Triggered by: <a href="' + CHANGE_URL + '">' + BRANCH_NAME +
                    ': ' + env.CHANGE_BRANCH + '</a> (' +  env.CHANGE_AUTHOR_DISPLAY_NAME + ')'
                }
        
                withMaven(maven: config.maven, jdk: config.jdk) {
                  script {
                    def mavenCommand = 'mvn ' +
                        params.extraMavenArguments +
                        ' -U -Dmaven.test.failure.ignore=true clean verify';
                        
                    if (isUnix()) {
                      sh script: mavenCommand
                    }
                    else {
                      bat script: mavenCommand
                    }
                  }
                }
                
                script {
                  def mavenConsoleIssues = scanForIssues tool: mavenConsole()
                  def javaIssues = scanForIssues tool: java()
                  def javaDocIssues = scanForIssues tool: javaDoc()
                  publishIssues id: "analysis-${PLATFORM}", issues: [mavenConsoleIssues, javaIssues, javaDocIssues]
                }
              }
            }
            
            // Perform a SNAPSHOT build of a main branch. This stage is typically executed after a
            // merge request has been merged. On success, it deploys the generated artifacts to the
            // Maven repository server.
            stage("SNAPSHOT build") {
              when { branch pattern: "main|main-v2", comparator: "REGEXP" }
              
              steps {
                withMaven(maven: config.maven, jdk: config.jdk) {
                  script {
                    def finalStep = PLATFORM == "ubuntu" ? "deploy" : "verify"
                    
                    def mavenCommand = 'mvn ' +
                      params.extraMavenArguments +
                      ' -U -Dmaven.test.failure.ignore=true clean deploy'
                      
                    if (isUnix()) {
                      sh script: mavenCommand
                    }
                    else {
                      bat script: mavenCommand
                    }
                  }
                }
                
                script {
                  def mavenConsoleIssues = scanForIssues tool: mavenConsole()
                  def javaIssues = scanForIssues tool: java()
                  def javaDocIssues = scanForIssues tool: javaDoc()
                  publishIssues id: "analysis-${PLATFORM}", issues: [mavenConsoleIssues, javaIssues, javaDocIssues]
                }
              }
            }
          }
          
          post {
            always {
              script {
                if (params.wipeWorkspaceAfterBuild) {
                  echo "Wiping workspace..."
                  cleanWs(cleanWhenNotBuilt: false,
                          deleteDirs: true,
                          disableDeferredWipeout: true,
                          notFailBuild: true)
                }
              }
            }
          }
        }
      }
    }
  }
}
