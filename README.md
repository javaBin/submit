# Legacy

We do not use this, anymore. Replaced by https://github.com/javaBin/submitthethird

# What is this?

This is an application that lets speakers submit talks to JavaZone.

Integrates with [Sleepingpill](https://github.com/javaBin/sleepingPillCore) which stores the talks.

# Getting started

* Check out repo: `git clone git@github.com:javaBin/submit.git`
* Build backend: `cd backend && mvn clean install`
* Build frontend: `cd frontend && npm install`
* Build nodeproxy: `cd scripts/proxy && npm install`
* Start local postgres: `cd localpostgres && vagrant up`
* Run the app: `./scripts/start.sh`

This starts a screen. To escape from the screen: `ctrl-x`, `q`, `y`.

# Running it in IntelliJ

Run the class `SubmitApplication`. Couldn't be easier ;)

# Debugging in Postman

We have created a Postman Collection to make it easy to test all the API calls of Submit when developing.

* Install [Postman](https://www.getpostman.com)
* Import the collection `submit.postman_collection.json`
* Setup Postman environment with key-value: `baseurl=https://submit.javazone.no` or `baseurl=http://localhost:8080`
* Start by running the `POST – Generate token` with your email address.
* Then you can do any other call using the token you got in an email in an `X-token` header.


# Application deployment to AWS

## First time: set up software and configure credentials

Install `aws` and `eb` command line tools, as well as `ansible`:

```
brew install awscli
brew install aws-elasticbeanstalk
brew install ansible
```

Edit/add the file `~/.aws/credentials` and add these lines

```
[javabin]
aws_access_key_id = <ADD YOURS HERE>
aws_secret_access_key = <ADD YOURS HERE>
```

## Every time: deploy the app to AWS

- `cd frontend && ./deploy.sh` (you'll be asked about which bucket to deploy to)
- `cd backend && ./deploy.sh submit-<env>`

The deploy needs the ansible vault password to be able to decrypt the property file. Ask around to get it :)

# Cloud tips and tricks :)

## SSH to the instance

You need the ssh key. Get the private and public key (`javabin` and `javabin.pub`) from someone who have them already, and place them in `~/.ssh`

Then you just do:
```
eb ssh submit-<env>
```

- Logs: `cd /var/log && tail -f web*.log nginx/*log`
- App files: `cd /var/app/current/`

## App property files for AWS

To edit which properties are used for deployment to AWS, edit the files in the `config` folder:

```
ansible-vault edit config/<env>.properties.encrypted
```

You need the vault password for this. Ask around to get access to it :)

## Create a new environment

You could probably just use test/prod which exists. 

If you need a new, do this and follow the instructions to get an environment up and running
```
eb create --region eu-central-1 --profile javabin
```

You need to setup a database + property files for backend + a frontend S3 bucket + load balancer as well. For now, this is a "manual process". Ask around... ;)
