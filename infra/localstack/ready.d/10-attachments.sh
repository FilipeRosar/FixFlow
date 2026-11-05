#!/bin/sh
set -eu

bucket="${S3_BUCKET:-fixflow-attachments}"
region="${AWS_DEFAULT_REGION:-sa-east-1}"

if ! awslocal s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    if [ "$region" = "us-east-1" ]; then
        awslocal s3api create-bucket --bucket "$bucket" --region "$region"
    else
        awslocal s3api create-bucket --bucket "$bucket" --region "$region" \
            --create-bucket-configuration "LocationConstraint=$region"
    fi
fi
awslocal s3api put-public-access-block --bucket "$bucket" \
    --public-access-block-configuration '{"BlockPublicAcls":true,"IgnorePublicAcls":true,"BlockPublicPolicy":true,"RestrictPublicBuckets":true}'
awslocal s3api put-bucket-ownership-controls --bucket "$bucket" \
    --ownership-controls '{"Rules":[{"ObjectOwnership":"BucketOwnerEnforced"}]}'
awslocal s3api put-bucket-encryption --bucket "$bucket" \
    --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
if [ -d /var/lib/localstack/fixflow-attachments ]; then
    awslocal s3 sync /var/lib/localstack/fixflow-attachments "s3://$bucket" --only-show-errors
fi
printf 'ready\n' > /tmp/fixflow-s3-ready
printf 'Bucket privado de anexos pronto: %s\n' "$bucket"
