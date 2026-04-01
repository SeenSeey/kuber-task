pipeline {
    agent any

    environment {
        DOCKER_HOST     = "tcp://172.17.0.1:2376"
        REGISTRY        = "172.17.0.1:5000"
        IMAGE_NAME      = "myapp"
        VAULT_ADDR      = "https://172.17.0.1:8200"
        VAULT_ROLE_ID   = credentials('vault-role-id')
        VAULT_SECRET_ID = credentials('vault-secret-id')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Get Secrets from Vault') {
            steps {
                script {
                    sh """
                        curl -sk \
                          -X POST \
                          -d '{"role_id":"${VAULT_ROLE_ID}","secret_id":"${VAULT_SECRET_ID}"}' \
                          ${VAULT_ADDR}/v1/auth/approle/login \
                          -o /tmp/vault-login.json

                        TOKEN=\$(jq -r .auth.client_token /tmp/vault-login.json)

                        # Клиентский сертификат для mTLS
                        curl -sk \
                          -H "X-Vault-Token: \$TOKEN" \
                          -X POST \
                          -d '{"common_name":"jenkins.local","ttl":"1h"}' \
                          ${VAULT_ADDR}/v1/pki/issue/internal-role \
                          -o /tmp/vault-cert.json

                        jq -r .data.certificate /tmp/vault-cert.json > /tmp/client.crt
                        jq -r .data.private_key  /tmp/vault-cert.json > /tmp/client.key
                        jq -r .data.issuing_ca   /tmp/vault-cert.json > /tmp/ca.pem

                        # Пароли registry
                        curl -sk \
                          -H "X-Vault-Token: \$TOKEN" \
                          ${VAULT_ADDR}/v1/secret/registry/users \
                          -o /tmp/vault-users.json

                        jq -r .data.writer /tmp/vault-users.json > /tmp/writer-pass.txt
                        jq -r .data.reader /tmp/vault-users.json > /tmp/reader-pass.txt

                        # Чистим файлы с токеном
                        rm -f /tmp/vault-login.json /tmp/vault-cert.json /tmp/vault-users.json
                    """
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
                sh """
                    cat /tmp/writer-pass.txt | docker --tlsverify \
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
                      build \
                      --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                      --target builder \
                      -t ${REGISTRY}/${IMAGE_NAME}:builder \
                      .

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

        stage('Push') {
            steps {
                sh """
                    cat /tmp/writer-pass.txt | docker --tlsverify \
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

        stage('Verify Reader Pull') {
            steps {
                sh """
                    cat /tmp/reader-pass.txt | docker --tlsverify \
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

    post {
        always {
            sh 'rm -f /tmp/client.crt /tmp/client.key /tmp/ca.pem /tmp/writer-pass.txt /tmp/reader-pass.txt'
        }
    }
}