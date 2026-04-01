pipeline {
    agent any

    environment {
        DOCKER_HOST  = "tcp://172.17.0.1:2376"
        REGISTRY     = "172.17.0.1:5000"
        IMAGE_NAME   = "myapp"
        SECRETS_PATH = "/opt/vault-agent/secrets"
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Verify Secrets Available') {
            steps {
                sh """
                    test -f ${SECRETS_PATH}/client.crt || (echo "ERROR: client cert not found" && exit 1)
                    test -f ${SECRETS_PATH}/client.key || (echo "ERROR: client key not found" && exit 1)
                    test -f ${SECRETS_PATH}/ca.pem     || (echo "ERROR: CA cert not found" && exit 1)
                    test -f ${SECRETS_PATH}/writer-pass.txt || (echo "ERROR: writer pass not found" && exit 1)
                    test -f ${SECRETS_PATH}/reader-pass.txt || (echo "ERROR: reader pass not found" && exit 1)
                    echo "All secrets available"
                """
            }
        }

        stage('Pull cache layers') {
            steps {
                sh """
                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:builder || true

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:latest || true
                """
            }
        }

        stage('Build') {
            steps {
                sh """
                    cat ${SECRETS_PATH}/writer-pass.txt | docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      login --username writer --password-stdin ${REGISTRY}

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      build \
                      --cache-from ${REGISTRY}/${IMAGE_NAME}:builder \
                      --target builder \
                      -t ${REGISTRY}/${IMAGE_NAME}:builder \
                      .

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
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
                    cat ${SECRETS_PATH}/writer-pass.txt | docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      login --username writer --password-stdin ${REGISTRY}

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:builder

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:${BUILD_NUMBER}

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      push ${REGISTRY}/${IMAGE_NAME}:latest
                """
            }
        }

        stage('Verify Reader Pull') {
            steps {
                sh """
                    cat ${SECRETS_PATH}/reader-pass.txt | docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      login --username reader --password-stdin ${REGISTRY}

                    docker --tlsverify \
                      --tlscacert=${SECRETS_PATH}/ca.pem \
                      --tlscert=${SECRETS_PATH}/client.crt \
                      --tlskey=${SECRETS_PATH}/client.key \
                      -H ${DOCKER_HOST} \
                      pull ${REGISTRY}/${IMAGE_NAME}:latest

                    echo "Reader pull: SUCCESS"
                """
            }
        }
    }

    post {
        always {
            sh """
                docker --tlsverify \
                --tlscacert=/opt/vault-agent/secrets/ca.pem \
                --tlscert=/opt/vault-agent/secrets/client.crt \
                --tlskey=/opt/vault-agent/secrets/client.key \
                -H tcp://172.17.0.1:2376 \
                logout 172.17.0.1:5000 || true
            """
        }
    }
}