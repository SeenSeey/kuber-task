#!/bin/bash

minikube start --driver=docker --extra-config=controller-manager.horizontal-pod-autoscaler-sync-period=1s --addons=ingress --ports=80:80,443:443,9090:9090
minikube addons enable ingress