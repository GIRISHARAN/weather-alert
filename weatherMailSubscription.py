import boto3
import json

# Initialize the DynamoDB and SNS clients
dynamodb = boto3.resource('dynamodb')
sns = boto3.client('sns')

# Name of the DynamoDB table
table_name = 'Weather_Locations_Test'

def lambda_handler(event, context):
  try:
    # Extract the Gmail and Location from the event
    gmail = event['gmail']
    location = event['location']
    
    # Put the data into the DynamoDB table
    table = dynamodb.Table(table_name)
    table.put_item(Item={'gmail': gmail, 'location': location})



    # Create a subscription filter policy
    filter_policy = {
    'email': [gmail]
  }

    # Subscribe the Gmail to the SNS topic with the filter policy
    topic_arn = 'arn:aws:sns:us-east-1:923540452677:Weather-SNS-Topic-Test'
    subscription = sns.subscribe(
    TopicArn=topic_arn,
    Protocol='email',
    Endpoint=gmail,
    Attributes={
    'FilterPolicy': json.dumps(filter_policy)
  }
    )
    
    return {
    'statusCode': 200,
    'body': 'Data stored in DynamoDB and Gmail subscribed to SNS topic with filter policy.'
  }
  except Exception as e:
    return {
    'statusCode': 500,
    'body': f'Error: {e}'
  }
