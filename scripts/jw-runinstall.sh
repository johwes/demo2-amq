#!/bin/bash

source /meetup-demo/multicloud-amq/scripts/exportEnvVars.sh

export KUBECONFIG=/meetup-demo/aws-ocp/auth/kubeconfig
./setup-aws-all
export KUBECONFIG=/meetup-demo/azr-ocp/auth/kubeconfig
./setup-azure-all
export KUBECONFIG=/meetup-demo/gcp-ocp/auth/kubeconfig
./setup-gcp-all

oc login -u kubeadmin -p UMeRe-hBQAi-JJ4Bi-8ynRD https://api.crc.testing:6443
./setup-onprem-all
