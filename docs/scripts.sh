#!/bin/bash

minikube start --driver=docker --extra-config=controller-manager.horizontal-pod-autoscaler-sync-period=1s --addons=ingress --ports=80:80,443:443
minikube addons enable ingress