# API Lifecycle Mockup

## Setup

Create an OpenShift project to hold all your artefacts:

```sh
oc project api-lifecycle
```

Create a secret that contains all your [3scale remotes](https://github.com/3scale/3scale_toolbox/blob/master/docs/remotes.md):

```sh
3scale remote add 3scale-saas https://$TOKEN@$TENANT.3scale.net/
3scale remote add 3scale-onprem https://$TOKEN@$TENANT.$DOMAIN/
oc create secret generic 3scale-toolbox --from-file=$HOME/.3scalerc.yaml
```

Deploy the sample API Backend:

```sh
oc project api-lifecycle
oc new-app -i openshift/redhat-openjdk18-openshift:1.4 https://github.com/microcks/api-lifecycle.git --context-dir=/beer-catalog-demo/api-implementation --name=beer-catalog
oc expose svc/beer-catalog --hostname=beer-catalog.app.itix.fr
```

Deploy APIcast instances to be used in APIcast self-managed instances:

```sh
oc create secret generic 3scale-tenant-saas --from-literal=password=https://$TOKEN@$TENANT-admin.3scale.net
oc create -f https://raw.githubusercontent.com/3scale/apicast/v3.4.0/openshift/apicast-template.yml
oc new-app --template=3scale-gateway --name=apicast-saas-staging -p CONFIGURATION_URL_SECRET=3scale-tenant-saas -p CONFIGURATION_CACHE=0 -p RESPONSE_CODES=true -p LOG_LEVEL=info -p CONFIGURATION_LOADER=lazy -p APICAST_NAME=apicast-saas-staging -p DEPLOYMENT_ENVIRONMENT=sandbox -p IMAGE_NAME=quay.io/3scale/apicast:v3.4.0
oc new-app --template=3scale-gateway --name=apicast-saas-production -p CONFIGURATION_URL_SECRET=3scale-tenant-saas -p CONFIGURATION_CACHE=60 -p RESPONSE_CODES=true -p LOG_LEVEL=info -p CONFIGURATION_LOADER=boot -p APICAST_NAME=apicast-saas-production -p DEPLOYMENT_ENVIRONMENT=production -p IMAGE_NAME=quay.io/3scale/apicast:v3.4.0
oc scale dc/apicast-saas-staging --replicas=1
oc scale dc/apicast-saas-production --replicas=1
oc create route edge apicast-saas-staging --service=apicast-saas-staging --hostname=wildcard.saas-staging.app.itix.fr --insecure-policy=Allow --wildcard-policy=Subdomain
oc create route edge apicast-saas-production --service=apicast-saas-production --hostname=wildcard.saas-production.app... --insecure-policy=Allow --wildcard-policy=Subdomain
```

## Usecases

| #                  | Format | Security | Target                           | Policies            |
|--------------------|--------|----------|----------------------------------|---------------------|
| [01](testcase-01/) | YAML   | API Key  | SaaS                             | -                   |
| [02](testcase-02/) | JSON   | Open     | Self-Managed, on-premises        | -                   |
| [03](testcase-03/) | JSON   | OIDC     | SaaS, Self-Managed, on-premises  | -                   |
| [04](testcase-04/) | YAML   | API Key  | Self-Managed, on-premises        | CORS                |
| [05](testcase-05/) | YAML   | API Key  | Self-Managed, on-premises        | URL rewriting       |
| [06](testcase-06/) | YAML   | API Key  | 3 envs on 1 tenant, Self-managed | -                   |
| [07](testcase-07/) | JSON   | OIDC     | 3 envs on 3 tenants, on-premises | CORS, URL rewriting |

### Usecase 01: Deploy a simple API on 3scale SaaS

```sh
oc process -f testcase-01/setup.yaml -p DEVELOPER_ACCOUNT_ID=2445582535751 -p PRIVATE_BASE_URL=http://beer-catalog.app.itix.fr |oc create -f -
```

### Usecase 02: Deploy an API on 3scale SaaS with self-managed APIcast and 3scale on-premises

```sh
oc process -f testcase-02/setup.yaml -p DEVELOPER_ACCOUNT_ID=2445582535751 -p PRIVATE_BASE_URL=http://beer-catalog.app.itix.fr -p TARGET_INSTANCE=3scale-saas -p PUBLIC_STAGING_WILDCARD_DOMAIN=nmasse-redhat-staging.app.itix.fr -p PUBLIC_PRODUCTION_WILDCARD_DOMAIN=nmasse-redhat-production.app.itix.fr |oc create -f -
```
