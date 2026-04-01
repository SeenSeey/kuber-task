pipeline {
    agent any

    environment {
        DOCKER_HOST   = "tcp://host.docker.internal:2376"
        REGISTRY      = "host.docker.internal:5000"
        IMAGE_NAME    = "myapp"
        VAULT_ADDR    = "https://host.docker.internal:8200"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Get Client Cert from Vault') {
            steps {
                withVault(
                    configuration: [
                        vaultUrl: "${VAULT_ADDR}",
                        vaultCredentialId: 'vault-approle',
                        engineVersion: 2,
                        skipSslVerification: true
                    ],
                    vaultSecrets: []
                ) {
                    script {
                        def certJson = sh(script: """
                            curl -sk \
                              -H "X-Vault-Token: \${VAULT_TOKEN}" \
                              -X POST \
                              -d '{"common_name":"jenkins.local","ttl":"1h"}' \
                              \${VAULT_ADDR}/v1/pki/issue/internal-role
                        """, returnStdout: true).trim()

                        writeFile file: '/tmp/client.crt',
                            text: sh(script: "echo '${certJson}' | jq -r .data.certificate",
                                     returnStdout: true).trim()
                        writeFile file: '/tmp/client.key',
                            text: sh(script: "echo '${certJson}' | jq -r .data.private_key",
                                     returnStdout: true).trim()
                        writeFile file: '/tmp/ca.pem',
                            text: sh(script: "echo '${certJson}' | jq -r .data.issuing_ca",
                                     returnStdout: true).trim()
                    }
                }
            }
        }

        stage('Pull cache layers') {
            steps {
                sh """
                    docker --tlsverify \
                      --tlscacert=/tmp/ca.pem \
                      --tlscert=/tmp/client.crt \
                      --tlskey=/tmp/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:builder || true

                    docker --tlsverify \
                      --tlscacert=/tmp/ca.pem \
                      --tlscert=/tmp/client.crt \
                      --tlskey=/tmp/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:latest || true
                """
            }
        }

        stage('Build') {
            steps {
                withVault(
                    configuration: [
                        vaultUrl: "${VAULT_ADDR}",
                        vaultCredentialId: 'vault-approle',
                        engineVersion: 2,
                        skipSslVerification: true
                    ],
                    vaultSecrets: [[
                        path: 'secret/registry/users',
                        engineVersion: 2,
                        secretValues: [
                            [envVar: 'WRITER_PASS', vaultKey: 'writer']
                        ]
                    ]]
                ) {
                    sh """
                        echo "\$WRITER_PASS" | docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          login --username writer --password-stdin \
                          ${REGISTRY}

                        # Сначала собираем builder-стейдж для кеширования
                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          build \
                          --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                          --target builder \
                          -t ${REGISTRY}/${IMAGE_NAME}:builder \
                          .

                        # Финальный образ
                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          build \
                          --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                          --cache-from ${REGISTRY}/${IMAGE_NAME}:latest \
                          -t ${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER} \
                          -t ${REGISTRY}/${IMAGE_NAME}:latest \
                          .
                    """
                }
            }
        }

        stage('Push') {
            steps {
                withVault(
                    configuration: [
                        vaultUrl: "${VAULT_ADDR}",
                        vaultCredentialId: 'vault-approle',
                        engineVersion: 2,
                        skipSslVerification: true
                    ],
                    vaultSecrets: [[
                        path: 'secret/registry/users',
                        engineVersion: 2,
                        secretValues: [
                            [envVar: 'WRITER_PASS', vaultKey: 'writer']
                        ]
                    ]]
                ) {
                    sh """
                        echo "\$WRITER_PASS" | docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          login --username writer --password-stdin \
                          ${REGISTRY}

                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          push ${REGISTRY}/${IMAGE_NAME}:builder

                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          push ${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER}

                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          push ${REGISTRY}/${IMAGE_NAME}:latest
                    """
                }
            }
        }

        stage('Verify Reader Pull') {
            steps {
                withVault(
                    configuration: [
                        vaultUrl: "${VAULT_ADDR}",
                        vaultCredentialId: 'vault-approle',
                        engineVersion: 2,
                        skipSslVerification: true
                    ],
                    vaultSecrets: [[
                        path: 'secret/registry/users',
                        engineVersion: 2,
                        secretValues: [
                            [envVar: 'READER_PASS', vaultKey: 'reader']
                        ]
                    ]]
                ) {
                    sh """
                        echo "\$READER_PASS" | docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          login --username reader --password-stdin \
                          ${REGISTRY}

                        docker --tlsverify \
                          --tlscacert=/tmp/ca.pem \
                          --tlscert=/tmp/client.crt \
                          --tlskey=/tmp/client.key \
                          -H ${DOCKER_HOST} \
                          pull ${REGISTRY}/${IMAGE_NAME}:latest

                        echo "Reader pull: SUCCESS"
                    """
                }
            }
        }
    }

    post {
        always {
            sh 'rm -f /tmp/client.crt /tmp/client.key /tmp/ca.pem'
        }
    }
}