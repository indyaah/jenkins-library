import hudson.AbortException

import org.junit.rules.TemporaryFolder

import com.lesfurets.jenkins.unit.BasePipelineTest

import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain


import util.JenkinsLoggingRule
import util.JenkinsShellCallRule
import util.JenkinsStepRule
import util.JenkinsEnvironmentRule
import util.Rules

class NeoDeployTest extends BasePipelineTest {
    @ClassRule
    public static TemporaryFolder tmp = new TemporaryFolder()

    private ExpectedException thrown = new ExpectedException().none()
    private JenkinsLoggingRule jlr = new JenkinsLoggingRule(this)
    private JenkinsShellCallRule jscr = new JenkinsShellCallRule(this)
    private JenkinsStepRule jsr = new JenkinsStepRule(this)
    private JenkinsEnvironmentRule jer = new JenkinsEnvironmentRule(this)

    @Rule
    public RuleChain ruleChain = Rules
        .getCommonRules(this)
        .around(thrown)
        .around(jlr)
        .around(jscr)
        .around(jsr)
        .around(jer)

    private static workspacePath
    private static warArchiveName
    private static propertiesFileName
    private static archiveName

    @BeforeClass
    static void createTestFiles() {

        workspacePath = "${tmp.getRoot()}"
        warArchiveName = 'warArchive.war'
        propertiesFileName = 'config.properties'
        archiveName = 'archive.mtar'

        tmp.newFile(warArchiveName) << 'dummy war archive'
        tmp.newFile(propertiesFileName) << 'dummy properties file'
        tmp.newFile(archiveName) << 'dummy archive'
    }

    @Before
    void init() {

        helper.registerAllowedMethod('dockerExecute', [Map, Closure], null)
        helper.registerAllowedMethod('fileExists', [String], { s -> return new File(workspacePath, s).exists() })
        helper.registerAllowedMethod('usernamePassword', [Map], { m -> return m })
        helper.registerAllowedMethod('withCredentials', [List, Closure], { l, c ->
            if(l[0].credentialsId == 'myCredentialsId') {
                binding.setProperty('username', 'anonymous')
                binding.setProperty('password', '********')
            } else if(l[0].credentialsId == 'CI_CREDENTIALS_ID') {
                binding.setProperty('username', 'defaultUser')
                binding.setProperty('password', '********')
            }
            try {
                c()
            } finally {
                binding.setProperty('username', null)
                binding.setProperty('password', null)
            }

        })

        binding.setVariable('env', ['NEO_HOME':'/opt/neo'])

        jer.env.configuration = [steps:[neoDeploy: [host: 'test.deploy.host.com', account: 'trialuser123']]]
    }


    @Test
    void straightForwardTestConfigViaConfigProperties() {

        jer.env.setConfigProperty('DEPLOY_HOST', 'test.deploy.host.com')
        jer.env.setConfigProperty('CI_DEPLOY_ACCOUNT', 'trialuser123')
        jer.env.configuration = [:]

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName,
                       neoCredentialsId: 'myCredentialsId'
        )

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy-mta --host 'test\.deploy\.host\.com' --account 'trialuser123' --synchronous --user 'anonymous' --password '\*\*\*\*\*\*\*\*' --source ".*"/
    }

    @Test
    void straightForwardTestConfigViaConfiguration() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId'
        )

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy-mta --host 'test\.deploy\.host\.com' --account 'trialuser123' --synchronous --user 'anonymous' --password '\*\*\*\*\*\*\*\*' --source ".*"/
    }

    @Test
    void straightForwardTestConfigViaConfigurationAndViaConfigProperties() {

        jer.env.setConfigProperty('DEPLOY_HOST', 'configProperties.deploy.host.com')
        jer.env.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        jer.env.configuration = [steps:[neoDeploy: [host: 'configuration-frwk.deploy.host.com',
                                                account: 'configurationFrwkUser123']]]

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
            archivePath: archiveName,
            neoCredentialsId: 'myCredentialsId'
        )

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy-mta --host 'configuration-frwk\.deploy\.host\.com' --account 'configurationFrwkUser123' --synchronous --user 'anonymous' --password '\*\*\*\*\*\*\*\*' --source ".*"/
    }


    @Test
    void badCredentialsIdTest() {

        thrown.expect(MissingPropertyException)
        thrown.expectMessage('No such property: username')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName,
                       neoCredentialsId: 'badCredentialsId'
        )
    }


    @Test
    void credentialsIdNotProvidedTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName
        )

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy-mta --host 'test\.deploy\.host\.com' --account 'trialuser123' --synchronous --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*"/
    }


    @Test
    void neoHomeNotSetTest() {

        binding.setVariable('env', [:])

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName
        )

        assert jscr.shell[0].contains('"neo.sh" deploy-mta')
        assert jlr.log.contains('Using Neo executable from PATH.')
    }


    @Test
    void neoHomeAsParameterTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName,
                       neoCredentialsId: 'myCredentialsId',
                       neoHome: '/etc/neo'
        )

        assert jscr.shell[0].contains('"/etc/neo/tools/neo.sh" deploy-mta')
        assert jlr.log.contains('[neoDeploy] Neo executable "/etc/neo/tools/neo.sh" retrieved from configuration.')
    }


    @Test
    void neoHomeFromEnvironmentTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName
        )

        assert jscr.shell[0].contains('"/opt/neo/tools/neo.sh" deploy-mta')
        assert jlr.log.contains('[neoDeploy] Neo executable "/opt/neo/tools/neo.sh" retrieved from environment.')
    }


    @Test
    void neoHomeFromCustomStepConfigurationTest() {

        jer.env.configuration = [steps:[neoDeploy: [host: 'test.deploy.host.com', account: 'trialuser123', neoHome: '/step/neo']]]

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: archiveName
        )

        assert jscr.shell[0].contains('"/step/neo/tools/neo.sh" deploy-mta')
        assert jlr.log.contains('[neoDeploy] Neo executable "/step/neo/tools/neo.sh" retrieved from configuration.')
    }


    @Test
    void archiveNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('Archive path not configured (parameter "archivePath").')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env])
    }


    @Test
    void wrongArchivePathProvidedTest() {

        thrown.expect(AbortException)
        thrown.expectMessage('Archive cannot be found')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                       archivePath: 'wrongArchiveName')
    }


    @Test
    void scriptNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('ERROR - NO VALUE AVAILABLE FOR host')

        jer.env.configuration = [:]

        jsr.step.call(archivePath: archiveName)
    }

    @Test
    void mtaDeployModeTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env], archivePath: archiveName, deployMode: 'mta')

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy-mta --host 'test\.deploy\.host\.com' --account 'trialuser123' --synchronous --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*"/
    }

    @Test
    void warFileParamsDeployModeTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             applicationName: 'testApp',
                             runtime: 'neo-javaee6-wp',
                             runtimeVersion: '2.125',
                             deployMode: 'warParams',
                             vmSize: 'lite',
                             warAction: 'deploy',
                             archivePath: warArchiveName)

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy --host 'test\.deploy\.host\.com' --account 'trialuser123' --application 'testApp' --runtime 'neo-javaee6-wp' --runtime-version '2\.125' --size 'lite' --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*\.war"/
    }

    @Test
    void warFileParamsDeployModeRollingUpdateTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             deployMode: 'warParams',
                             applicationName: 'testApp',
                             runtime: 'neo-javaee6-wp',
                             runtimeVersion: '2.125',
                             warAction: 'rolling-update',
                             vmSize: 'lite')

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" rolling-update --host 'test\.deploy\.host\.com' --account 'trialuser123' --application 'testApp' --runtime 'neo-javaee6-wp' --runtime-version '2\.125' --size 'lite' --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*\.war"/
    }

    @Test
    void warPropertiesFileDeployModeTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             deployMode: 'warPropertiesFile',
                             propertiesFile: propertiesFileName,
                             applicationName: 'testApp',
                             runtime: 'neo-javaee6-wp',
                             runtimeVersion: '2.125',
                             warAction: 'deploy',
                             vmSize: 'lite')

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" deploy .*\.properties --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*\.war"/
    }

    @Test
    void warPropertiesFileDeployModeRollingUpdateTest() {

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             deployMode: 'warPropertiesFile',
                             propertiesFile: propertiesFileName,
                             applicationName: 'testApp',
                             runtime: 'neo-javaee6-wp',
                             runtimeVersion: '2.125',
                             warAction: 'rolling-update',
                             vmSize: 'lite')

        assert jscr.shell[0] =~ /#!\/bin\/bash "\/opt\/neo\/tools\/neo\.sh" rolling-update .*\.properties --user 'defaultUser' --password '\*\*\*\*\*\*\*\*' --source ".*\.war"/
    }

    @Test
    void applicationNameNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('ERROR - NO VALUE AVAILABLE FOR applicationName')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             deployMode: 'warParams',
                             runtime: 'neo-javaee6-wp',
                             runtimeVersion: '2.125'
            )
    }

    @Test
    void runtimeNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('ERROR - NO VALUE AVAILABLE FOR runtime')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             applicationName: 'testApp',
                             deployMode: 'warParams',
                             runtimeVersion: '2.125')
    }

    @Test
    void runtimeVersionNotProvidedTest() {

        thrown.expect(Exception)
        thrown.expectMessage('ERROR - NO VALUE AVAILABLE FOR runtimeVersion')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: warArchiveName,
                             applicationName: 'testApp',
                             deployMode: 'warParams',
                             runtime: 'neo-javaee6-wp')
    }

    @Test
    void illegalDeployModeTest() {

        thrown.expect(Exception)
        thrown.expectMessage("[neoDeploy] Invalid deployMode = 'illegalMode'. Valid 'deployMode' values are: 'mta', 'warParams' and 'warPropertiesFile'")

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
            archivePath: warArchiveName,
            deployMode: 'illegalMode',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'deploy',
            vmSize: 'lite')
    }

    @Test
    void illegalVMSizeTest() {

        thrown.expect(Exception)
        thrown.expectMessage("[neoDeploy] Invalid vmSize = 'illegalVM'. Valid 'vmSize' values are: 'lite', 'pro', 'prem' and 'prem-plus'.")

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
            archivePath: warArchiveName,
            deployMode: 'warParams',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'deploy',
            vmSize: 'illegalVM')
    }

    @Test
    void illegalWARActionTest() {

        thrown.expect(Exception)
        thrown.expectMessage("[neoDeploy] Invalid warAction = 'illegalWARAction'. Valid 'warAction' values are: 'deploy' and 'rolling-update'.")

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
            archivePath: warArchiveName,
            deployMode: 'warParams',
            applicationName: 'testApp',
            runtime: 'neo-javaee6-wp',
            runtimeVersion: '2.125',
            warAction: 'illegalWARAction',
            vmSize: 'lite')
    }

    @Test
    void deployHostProvidedAsDeprecatedParameterTest() {

        jer.env.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: archiveName,
                             deployHost: "my.deploy.host.com"
        )

        assert jlr.log.contains("[WARNING][neoDeploy] Deprecated parameter 'deployHost' is used. This will not work anymore in future versions. Use parameter 'host' instead.")
    }

    @Test
    void deployAccountProvidedAsDeprecatedParameterTest() {

        jer.env.setConfigProperty('CI_DEPLOY_ACCOUNT', 'configPropsUser123')

        jsr.step.call(script: [commonPipelineEnvironment: jer.env],
                             archivePath: archiveName,
                             host: "my.deploy.host.com",
                             deployAccount: "myAccount"
        )

        assert jlr.log.contains("Deprecated parameter 'deployAccount' is used. This will not work anymore in future versions. Use parameter 'account' instead.")
    }
}
