# Usecase "Hybrid - OIDC": Deploy an API secured with OpenID Connect on self-managed APIcast and 3scale on-premises

In this usecase, a [Jenkins pipeline](Jenkinsfile) will deploy an API described by an [OpenAPI Specification file](swagger.json) on a 3scale SaaS instance with self-managed APIcast and on a 3scale on-prem instance. The API is secured using OpenID Connect as requested in the OAS.

## Pre-requisites

Make sure you completed the [SETUP guide](../SETUP.md).

## Installation

Use the [provided OpenShift template](setup.yaml) to install the Jenkins pipeline configured to target 3scale SaaS:

```sh
oc process -f hybrid-usecase-oidc/setup.yaml \
           -p DEVELOPER_ACCOUNT_ID="$SAAS_DEVELOPER_ACCOUNT_ID" \
           -p PRIVATE_BASE_URL="http://$BEER_CATALOG_HOSTNAME" \
           -p TARGET_INSTANCE=3scale-saas \
           -p PUBLIC_STAGING_WILDCARD_DOMAIN="$APICAST_SELF_MANAGED_STAGING_WILDCARD_DOMAIN" \
           -p PUBLIC_PRODUCTION_WILDCARD_DOMAIN="$APICAST_SELF_MANAGED_PRODUCTION_WILDCARD_DOMAIN" \
           -p OIDC_ISSUER_ENDPOINT="https://$CLIENT_ID:$CLIENT_SECRET@$SSO_HOSTNAME/auth/realms/$REALM" \
           -p NAMESPACE="$TOOLBOX_NAMESPACE" |oc create -f -
```

Use the [provided OpenShift template](setup.yaml) to install the Jenkins pipeline configured to target 3scale on-prem:

```sh
oc process -f hybrid-usecase-oidc/setup.yaml \
           -p DEVELOPER_ACCOUNT_ID="$ONPREM_DEVELOPER_ACCOUNT_ID" \
           -p PRIVATE_BASE_URL="http://$BEER_CATALOG_HOSTNAME" \
           -p TARGET_INSTANCE=3scale-onprem \
           -p PUBLIC_STAGING_WILDCARD_DOMAIN="$APICAST_ONPREM_STAGING_WILDCARD_DOMAIN" \
           -p PUBLIC_PRODUCTION_WILDCARD_DOMAIN="$APICAST_ONPREM_PRODUCTION_WILDCARD_DOMAIN" \
           -p OIDC_ISSUER_ENDPOINT="https://$CLIENT_ID:$CLIENT_SECRET@$SSO_HOSTNAME/auth/realms/$REALM" \
           -p DISABLE_TLS_VALIDATION=yes \
           -p NAMESPACE="$TOOLBOX_NAMESPACE" |oc create -f -
```

## Deployment

Deploy the API to 3scale SaaS:

```sh
oc start-build hybrid-usecase-oidc-3scale-saas
```

Deploy the API to 3scale on-prem:

```sh
oc start-build hybrid-usecase-oidc-3scale-onprem
```
